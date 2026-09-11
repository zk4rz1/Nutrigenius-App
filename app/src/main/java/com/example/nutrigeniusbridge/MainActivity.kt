package com.example.nutrigeniusbridge

import android.annotation.SuppressLint
import android.os.Bundle
import android.webkit.WebChromeClient
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.ComponentActivity
import androidx.health.connect.client.PermissionController
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import android.widget.FrameLayout
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : ComponentActivity() {

    companion object {
        /** Extra dell'intent con cui la scorciatoia «Scatta foto» chiede alla PWA di aprire la fotocamera. */
        const val EXTRA_AZIONE = "azione"
        const val AZIONE_SCATTA = "scatta"
        private const val ID_SCORCIATOIA_SCATTA = "scatta"
    }

    private lateinit var webView: WebView
    private var paginaCaricata = false
    private lateinit var healthConnectManager: HealthConnectManager
    private lateinit var webAppInterface: HealthConnectWebAppInterface
    private lateinit var errorManager: ErrorManager

    private var pendingCallback: String = ""

    private var fileChooserCallback: android.webkit.ValueCallback<Array<android.net.Uri>>? = null
    private var cameraUri: android.net.Uri? = null
    private var webViewPermissionRequest: android.webkit.PermissionRequest? = null

    private val fileChooserLauncher = registerForActivityResult(androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == android.app.Activity.RESULT_OK) {
            var results: Array<android.net.Uri>? = null
            if (result.data?.clipData != null) {
                val clipData = result.data!!.clipData!!
                val uris = mutableListOf<android.net.Uri>()
                for (i in 0 until clipData.itemCount) {
                    uris.add(clipData.getItemAt(i).uri)
                }
                results = uris.toTypedArray()
            } else if (result.data?.data != null) {
                results = arrayOf(result.data!!.data!!)
            } else {
                results = android.webkit.WebChromeClient.FileChooserParams.parseResult(result.resultCode, result.data)
            }
            
            if (results != null && results.isNotEmpty()) {
                CoroutineScope(Dispatchers.IO).launch {
                    val processedResults = results.mapNotNull { uri ->
                        try {
                            var fileName = "imported_file"
                            contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                                if (cursor.moveToFirst()) {
                                    val idx = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                                    if (idx != -1) fileName = cursor.getString(idx)
                                }
                            }
                            // Ensure unique file name to avoid collisions when selecting multiple files
                            val uniqueName = "${System.currentTimeMillis()}_${java.util.UUID.randomUUID().toString().take(4)}_$fileName"
                            val tempFile = java.io.File(cacheDir, uniqueName)
                            contentResolver.openInputStream(uri)?.use { input ->
                                tempFile.outputStream().use { output ->
                                    input.copyTo(output)
                                }
                            }
                            androidx.core.content.FileProvider.getUriForFile(this@MainActivity, "$packageName.fileprovider", tempFile)
                        } catch (e: Exception) {
                            uri
                        }
                    }.toTypedArray()
                    withContext(Dispatchers.Main) {
                        fileChooserCallback?.onReceiveValue(processedResults)
                        fileChooserCallback = null
                    }
                }
                cameraUri = null
                return@registerForActivityResult
            } else if (cameraUri != null) {
                fileChooserCallback?.onReceiveValue(arrayOf(cameraUri!!))
            } else {
                fileChooserCallback?.onReceiveValue(null)
            }
        } else {
            fileChooserCallback?.onReceiveValue(null)
        }
        fileChooserCallback = null
        cameraUri = null
    }

    private val cameraPermissionLauncher = registerForActivityResult(androidx.activity.result.contract.ActivityResultContracts.RequestMultiplePermissions()) { granted ->
        if (granted[android.Manifest.permission.CAMERA] == true) {
            webViewPermissionRequest?.grant(webViewPermissionRequest?.resources)
        } else {
            webViewPermissionRequest?.deny()
        }
        webViewPermissionRequest = null
    }

    private val permissionLauncher =
        registerForActivityResult(PermissionController.createRequestPermissionResultContract()) { granted ->
            // Permissions resolved (granted or denied). Return control to PWA.
            if (pendingCallback.isNotEmpty()) {
                webView.evaluateJavascript("javascript:if(window.$pendingCallback) { window.$pendingCallback(); }", null)
                pendingCallback = ""
            }
        }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        errorManager = ErrorManager(this)
        
        val defaultUncaughtHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, exception ->
            val details = exception.stackTraceToString()
            errorManager.logError("Crash di Sistema: ${exception.message}", details, "AndroidNative", false)
            defaultUncaughtHandler?.uncaughtException(thread, exception)
        }
        
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
            if (androidx.core.content.ContextCompat.checkSelfPermission(this, android.Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                registerForActivityResult(androidx.activity.result.contract.ActivityResultContracts.RequestPermission()) {}.launch(android.Manifest.permission.POST_NOTIFICATIONS)
            }
        }

        healthConnectManager = HealthConnectManager(this)
        
        // Enable edge-to-edge
        androidx.core.view.WindowCompat.setDecorFitsSystemWindows(window, false)

        val rootView = FrameLayout(this)
        
        webView = WebView(this).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.databaseEnabled = true
            settings.cacheMode = WebSettings.LOAD_DEFAULT
            settings.mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
            settings.allowFileAccess = true
            settings.allowContentAccess = true
            
            webViewClient = object : WebViewClient() {
                override fun onReceivedError(view: WebView?, request: android.webkit.WebResourceRequest?, error: android.webkit.WebResourceError?) {
                    super.onReceivedError(view, request, error)
                    if (request?.isForMainFrame == true) {
                        errorManager.logError("Caricamento fallito", error?.description.toString(), "WebView", true)
                        android.widget.Toast.makeText(this@MainActivity, "Caricamento fallito: ${error?.description}", android.widget.Toast.LENGTH_LONG).show()
                    }
                }

                override fun onReceivedHttpError(view: WebView?, request: android.webkit.WebResourceRequest?, errorResponse: android.webkit.WebResourceResponse?) {
                    super.onReceivedHttpError(view, request, errorResponse)
                    if (request?.isForMainFrame == true) {
                        errorManager.logError("Errore HTTP", errorResponse?.statusCode.toString(), "WebView", true)
                        android.widget.Toast.makeText(this@MainActivity, "Errore HTTP: ${errorResponse?.statusCode}", android.widget.Toast.LENGTH_LONG).show()
                    }
                }

                override fun onReceivedSslError(view: WebView?, handler: android.webkit.SslErrorHandler?, error: android.net.http.SslError?) {
                    errorManager.logError("Errore SSL", error?.toString() ?: "", "WebView", true)
                    android.widget.Toast.makeText(this@MainActivity, "Errore SSL. Ignoro per test.", android.widget.Toast.LENGTH_LONG).show()
                    handler?.proceed() // Temporarily proceed to see if it fixes it
                }

                override fun onPageFinished(view: WebView?, url: String?) {
                    super.onPageFinished(view, url)
                    paginaCaricata = true
                    val js = """
                        javascript:(function() {
                            var lastAction = '';
                            document.addEventListener('click', function(e) {
                                var target = e.target.closest('button, a, input, select');
                                if (target) {
                                    lastAction = 'Click su ' + target.tagName + (target.innerText ? ' (' + target.innerText.substring(0, 20) + ')' : '');
                                }
                            }, true);
                            
                            var originalConsoleError = console.error;
                            console.error = function() {
                                originalConsoleError.apply(console, arguments);
                                var msg = Array.from(arguments).join(' ');
                                if(window.NutriGeniusErrorBridge) {
                                    window.NutriGeniusErrorBridge.logJsError(msg, "console.error", 0, 0, "", true, lastAction, window.location.hash || window.location.pathname);
                                }
                            };
                            
                            window.onerror = function(message, source, lineno, colno, error) {
                                if(window.NutriGeniusErrorBridge) {
                                    window.NutriGeniusErrorBridge.logJsError(message, source, lineno, colno, error ? error.stack : "", false, lastAction, window.location.hash || window.location.pathname);
                                }
                                return false;
                            };

                            window.addEventListener('unhandledrejection', function(event) {
                                if(window.NutriGeniusErrorBridge) {
                                    window.NutriGeniusErrorBridge.logJsError("Unhandled Promise: " + event.reason, "Promise", 0, 0, "", false, lastAction, window.location.hash || window.location.pathname);
                                }
                            });
                            
                            window.AndroidHealthConnect = {
                                minimizeApp: function() {
                                    window.HealthConnectBridge.minimizeApp();
                                }
                            };
                            
                            window.PushManager = window.PushManager || function() {};
                            var fakeSub = {
                                endpoint: 'https://android-native-push.local',
                                keys: { p256dh: 'fake', auth: 'fake' },
                                toJSON: function() { return this; },
                                unsubscribe: function() { return Promise.resolve(true); }
                            };
                            
                            try {
                                if (typeof ServiceWorkerRegistration !== 'undefined' && !Object.getOwnPropertyDescriptor(ServiceWorkerRegistration.prototype, 'pushManager')) {
                                    Object.defineProperty(ServiceWorkerRegistration.prototype, 'pushManager', {
                                        get: function() {
                                            return {
                                                getSubscription: function() { return Promise.resolve(fakeSub); },
                                                subscribe: function() { return Promise.resolve(fakeSub); }
                                            };
                                        },
                                        configurable: true,
                                        enumerable: true
                                    });
                                } else if (!navigator.serviceWorker) {
                                    if (!Object.getOwnPropertyDescriptor(navigator, 'serviceWorker')) {
                                        Object.defineProperty(navigator, 'serviceWorker', {
                                            value: {
                                                ready: Promise.resolve({
                                                    pushManager: {
                                                        getSubscription: function() { return Promise.resolve(fakeSub); },
                                                        subscribe: function() { return Promise.resolve(fakeSub); }
                                                    }
                                                })
                                            },
                                            configurable: true
                                        });
                                    }
                                }
                            } catch(e) { console.error("PushMock error: " + e.message); }
                            
                            var originalFetch = window.fetch;
                            window.fetch = async function() {
                                var url = arguments[0];
                                if (typeof url === 'string') {
                                    if (url.includes('/api/push/subscribe')) {
                                        return new Response('{"success": true}', { status: 200, headers: {'Content-Type': 'application/json'} });
                                    }
                                    if (url.includes('/api/push/scheduleWater')) {
                                        try {
                                            var opts = arguments[1];
                                            if (opts && opts.body) {
                                                var bodyData = JSON.parse(opts.body);
                                                var payload = bodyData.payload || {};
                                                if (payload.action === 'close') {
                                                    window.HealthConnectBridge.cancelNotification();
                                                } else {
                                                    var delayMs = bodyData.delayMs || 0;
                                                    window.HealthConnectBridge.scheduleNotification(payload.title || 'NutriGenius', payload.body || '', delayMs);
                                                }
                                            }
                                        } catch(e) {}
                                        return new Response('{"success": true}', { status: 200, headers: {'Content-Type': 'application/json'} });
                                    }
                                }
                                return originalFetch.apply(this, arguments);
                            };
                            
                            window.Notification = function(title, options) {
                                window.HealthConnectBridge.showNotification(title, options ? options.body || '' : '');
                            };
                            window.Notification.requestPermission = function() {
                                return Promise.resolve('granted');
                            };
                            window.Notification.permission = 'granted';

                            if (navigator.clipboard) {
                                navigator.clipboard.writeText = function(text) {
                                    return new Promise(function(resolve, reject) {
                                        try {
                                            window.HealthConnectBridge.copyToClipboard(text);
                                            resolve();
                                        } catch(e) { reject(e); }
                                    });
                                };
                            }
                            
                            var handleBlobDownload = function(href, filename) {
                                var xhr = new XMLHttpRequest();
                                xhr.open('GET', href, true);
                                xhr.responseType = 'blob';
                                xhr.onload = function() {
                                    var reader = new FileReader();
                                    reader.readAsDataURL(xhr.response);
                                    reader.onloadend = function() {
                                        var base64data = reader.result;
                                        window.HealthConnectBridge.saveBlobFile(base64data, filename, xhr.response.type);
                                    }
                                };
                                xhr.send();
                            };

                            var originalClick = HTMLAnchorElement.prototype.click;
                            HTMLAnchorElement.prototype.click = function() {
                                if (this.hasAttribute('download') && this.href.startsWith('blob:')) {
                                    handleBlobDownload(this.href, this.download);
                                    return;
                                }
                                originalClick.apply(this, arguments);
                            };
                            
                            document.addEventListener('click', function(e) {
                                var a = e.target.closest('a');
                                if (a && a.hasAttribute('download') && a.href.startsWith('blob:')) {
                                    e.preventDefault();
                                    handleBlobDownload(a.href, a.download);
                                }
                            }, true);
                        })();
                    """.trimIndent()
                    view?.evaluateJavascript(js, null)
                }
            }

            webChromeClient = object : WebChromeClient() {
                override fun onConsoleMessage(consoleMessage: android.webkit.ConsoleMessage?): Boolean {
                    return super.onConsoleMessage(consoleMessage)
                }

                override fun onPermissionRequest(request: android.webkit.PermissionRequest?) {
                    if (request?.resources?.contains(android.webkit.PermissionRequest.RESOURCE_VIDEO_CAPTURE) == true) {
                        if (androidx.core.content.ContextCompat.checkSelfPermission(this@MainActivity, android.Manifest.permission.CAMERA) == android.content.pm.PackageManager.PERMISSION_GRANTED) {
                            request.grant(request.resources)
                        } else {
                            webViewPermissionRequest = request
                            cameraPermissionLauncher.launch(arrayOf(android.Manifest.permission.CAMERA, android.Manifest.permission.RECORD_AUDIO))
                        }
                    } else {
                        request?.grant(request.resources)
                    }
                }

                override fun onShowFileChooser(
                    webView: WebView?,
                    filePathCallback: android.webkit.ValueCallback<Array<android.net.Uri>>?,
                    fileChooserParams: FileChooserParams?
                ): Boolean {
                    fileChooserCallback?.onReceiveValue(null)
                    fileChooserCallback = filePathCallback
                    
                    try {
                        if (fileChooserParams?.isCaptureEnabled == true) {
                            val file = java.io.File(cacheDir, "camera_capture_${System.currentTimeMillis()}.jpg")
                            cameraUri = androidx.core.content.FileProvider.getUriForFile(this@MainActivity, "$packageName.fileprovider", file)
                            val cameraIntent = android.content.Intent(android.provider.MediaStore.ACTION_IMAGE_CAPTURE).apply {
                                putExtra(android.provider.MediaStore.EXTRA_OUTPUT, cameraUri)
                            }
                            fileChooserLauncher.launch(cameraIntent)
                            return true
                        }

                        // Solo immagini richieste: il selettore di foto di Android
                        // (galleria e album), non il gestore dei file.
                        val tipi = fileChooserParams?.acceptTypes?.filter { it.isNotBlank() } ?: emptyList()
                        val soloImmagini = tipi.isNotEmpty() && tipi.all { it.startsWith("image/") || it.startsWith(".jp") || it.startsWith(".png") || it.startsWith(".webp") }
                        if (soloImmagini) {
                            val multiplo = fileChooserParams?.mode == android.webkit.WebChromeClient.FileChooserParams.MODE_OPEN_MULTIPLE
                            val galleria = android.content.Intent(android.provider.MediaStore.ACTION_PICK_IMAGES).apply {
                                type = "image/*"
                                if (multiplo) putExtra(android.provider.MediaStore.EXTRA_PICK_IMAGES_MAX, android.provider.MediaStore.getPickImagesMaxLimit())
                            }
                            fileChooserLauncher.launch(galleria)
                            return true
                        }

                        val intent = android.content.Intent(android.content.Intent.ACTION_OPEN_DOCUMENT).apply {
                            addCategory(android.content.Intent.CATEGORY_OPENABLE)
                            type = "*/*"
                            if (fileChooserParams?.acceptTypes?.isNotEmpty() == true) {
                                putExtra(android.content.Intent.EXTRA_MIME_TYPES, fileChooserParams.acceptTypes)
                            }
                            if (fileChooserParams?.mode == android.webkit.WebChromeClient.FileChooserParams.MODE_OPEN_MULTIPLE) {
                                putExtra(android.content.Intent.EXTRA_ALLOW_MULTIPLE, true)
                            }
                        }
                        fileChooserLauncher.launch(intent)
                    } catch (e: Exception) {
                        fileChooserCallback?.onReceiveValue(null)
                        fileChooserCallback = null
                        return false
                    }
                    return true
                }
            }
        }
        
        rootView.addView(webView, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT, 
            FrameLayout.LayoutParams.MATCH_PARENT
        ))
        
        val statusBarBg = android.view.View(this).apply {
            setBackgroundColor(android.graphics.Color.parseColor("#6366f1"))
        }
        rootView.addView(statusBarBg, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, 0))

        val navBarBg = android.view.View(this).apply {
            setBackgroundColor(android.graphics.Color.WHITE)
        }
        val navBarParams = FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, 0)
        navBarParams.gravity = android.view.Gravity.BOTTOM
        rootView.addView(navBarBg, navBarParams)

        ViewCompat.setOnApplyWindowInsetsListener(rootView) { _, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            
            val webParams = webView.layoutParams as FrameLayout.LayoutParams
            webParams.setMargins(0, systemBars.top, 0, systemBars.bottom)
            webView.layoutParams = webParams

            val sbParams = statusBarBg.layoutParams
            sbParams.height = systemBars.top
            statusBarBg.layoutParams = sbParams
            
            val nbParams = navBarBg.layoutParams
            nbParams.height = systemBars.bottom
            navBarBg.layoutParams = nbParams

            insets
        }

        webAppInterface = HealthConnectWebAppInterface(this, healthConnectManager, webView, errorManager)
        webView.addJavascriptInterface(webAppInterface, "HealthConnectBridge")
        webView.addJavascriptInterface(NutriGeniusErrorBridge(errorManager, webView), "NutriGeniusErrorBridge")

        setContentView(rootView)

        // Force transparent system bars so our custom backgrounds show through
        window.statusBarColor = android.graphics.Color.TRANSPARENT
        window.navigationBarColor = android.graphics.Color.TRANSPARENT
        androidx.core.view.WindowInsetsControllerCompat(window, window.decorView).apply {
            isAppearanceLightStatusBars = false // White icons on purple background
            isAppearanceLightNavigationBars = true // Dark icons on white nav background
        }

        // Handle Back button properly for modern Android
        onBackPressedDispatcher.addCallback(this, object : androidx.activity.OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                // Delegate to the WebView's JS history API. 
                // The PWA handles popstate and manages modals internally.
                webView.evaluateJavascript("window.history.back()", null)
            }
        })

        registraScorciatoie()

        // Load the PWA: dalla scorciatoia «Scatta foto» parte direttamente con
        // la fotocamera (la PWA riconosce ?scatta=1 come la sua scorciatoia).
        webView.loadUrl(if (chiedeScatto(intent)) BuildConfig.WEBAPP_URL + "?scatta=1" else BuildConfig.WEBAPP_URL)
    }

    /**
     * La scorciatoia «Scatta foto» (pressione lunga sull'icona, trascinabile in
     * Home): dinamica e non in XML, così il pacchetto di destinazione è quello
     * giusto anche per il flavor dev, che ha il suffisso .dev.
     */
    private fun registraScorciatoie() {
        try {
            val intent = android.content.Intent(this, MainActivity::class.java).apply {
                action = android.content.Intent.ACTION_VIEW
                putExtra(EXTRA_AZIONE, AZIONE_SCATTA)
            }
            val scorciatoia = androidx.core.content.pm.ShortcutInfoCompat.Builder(this, ID_SCORCIATOIA_SCATTA)
                .setShortLabel(getString(R.string.scorciatoia_scatta_breve))
                .setLongLabel(getString(R.string.scorciatoia_scatta_lunga))
                .setIcon(androidx.core.graphics.drawable.IconCompat.createWithResource(this, R.drawable.ic_shortcut_scatta))
                .setIntent(intent)
                .build()
            androidx.core.content.pm.ShortcutManagerCompat.setDynamicShortcuts(this, listOf(scorciatoia))
        } catch (e: Exception) {
            errorManager.logError("Scorciatoia non registrata", e.stackTraceToString(), "AndroidNative", false)
        }
    }

    private fun chiedeScatto(intent: android.content.Intent?): Boolean =
        intent?.getStringExtra(EXTRA_AZIONE) == AZIONE_SCATTA

    /** L'app era già aperta: alla PWA basta un evento, senza ricaricare la pagina. */
    override fun onNewIntent(intent: android.content.Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (!chiedeScatto(intent)) return
        if (!paginaCaricata) {
            webView.loadUrl(BuildConfig.WEBAPP_URL + "?scatta=1")
            return
        }
        // La PWA aggiornata espone window.__nutrigeniusScatta e risponde 'ok';
        // una versione più vecchia no, e allora si ricarica con ?scatta=1, che
        // capisce comunque. Così la scorciatoia funziona in ogni caso.
        webView.evaluateJavascript(
            "(function(){ if (window.__nutrigeniusScatta) { window.__nutrigeniusScatta(); return 'ok'; } " +
                "window.dispatchEvent(new CustomEvent('nutrigenius-scatta')); return 'evento'; })()"
        ) { esito ->
            if (esito != "\"ok\"") webView.loadUrl(BuildConfig.WEBAPP_URL + "?scatta=1")
        }
    }

    fun requestSpecificPermissions(recordNames: List<String>, callbackFunctionName: String?) {
        pendingCallback = callbackFunctionName ?: ""
        val allPermissions = recordNames.flatMap { 
            healthConnectManager.getPermissionsForRecord(it) 
        }.toSet()
        if (allPermissions.isNotEmpty()) {
            permissionLauncher.launch(allPermissions)
        } else {
            if (pendingCallback.isNotEmpty()) {
                webView.evaluateJavascript("javascript:if(window.$pendingCallback) { window.$pendingCallback(); }", null)
                pendingCallback = ""
            }
        }
    }

    fun requestAllPermissions() {
        val allPermissions = healthConnectManager.recordTypesMap.keys.flatMap { 
            healthConnectManager.getPermissionsForRecord(it) 
        }.toSet()
        if (allPermissions.isNotEmpty()) {
            permissionLauncher.launch(allPermissions)
        }
    }
    

}
