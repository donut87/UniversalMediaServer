package net.pms.remote;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.net.CookieHandler;
import java.net.CookieManager;
import java.net.HttpCookie;
import java.net.HttpURLConnection;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URL;
import java.net.URLConnection;
import java.nio.charset.StandardCharsets;
import java.security.AccessController;
import java.security.GeneralSecurityException;
import java.security.KeyStore;
import java.security.PrivilegedAction;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Executors;
import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLEngine;
import javax.net.ssl.SSLParameters;
import javax.net.ssl.TrustManagerFactory;
import com.samskivert.mustache.MustacheException;
import com.samskivert.mustache.Template;
import com.sun.net.httpserver.BasicAuthenticator;
import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpContext;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import com.sun.net.httpserver.HttpsConfigurator;
import com.sun.net.httpserver.HttpsParameters;
import com.sun.net.httpserver.HttpsServer;
import net.pms.PMS;
import net.pms.configuration.PmsConfiguration;
import net.pms.configuration.RendererConfiguration;
import net.pms.configuration.WebRender;
import net.pms.dlna.DLNAResource;
import net.pms.dlna.DLNAThumbnailInputStream;
import net.pms.dlna.RealFile;
import net.pms.dlna.RootFolder;
import net.pms.dlna.virtual.MediaLibraryFolder;
import net.pms.image.BufferedImageFilterChain;
import net.pms.image.ImageFormat;
import net.pms.network.HTTPResource;
import net.pms.newgui.DbgPacker;
import net.pms.util.FileUtil;
import net.pms.util.FullyPlayed;
import org.apache.commons.configuration.ConfigurationException;
import org.apache.commons.lang.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@SuppressWarnings("restriction")
public class RemoteWeb {
	private static final Logger LOGGER = LoggerFactory.getLogger(RemoteWeb.class);
	private KeyStore keyStore;
	private KeyManagerFactory keyManagerFactory;
	private TrustManagerFactory trustManagerFactory;
	private HttpServer server;
	private SSLContext sslContext;
	private Map<String, RootFolder> roots;
	private RemoteUtil.ResourceManager resources;
	private static final PmsConfiguration CONFIGURATION = PMS.getConfiguration();
	private static final int DEFAULT_PORT = CONFIGURATION.getWebPort();

	public RemoteWeb() throws IOException {
		this(DEFAULT_PORT);
	}

	public RemoteWeb(int port) throws IOException {
		if (port <= 0) {
			port = DEFAULT_PORT;
		}

		roots = new HashMap<>();
		// Add "classpaths" for resolving web resources
		resources = AccessController.doPrivileged(new PrivilegedAction<RemoteUtil.ResourceManager>() {

			@Override
			public RemoteUtil.ResourceManager run() {
				return new RemoteUtil.ResourceManager(
					"file:" + CONFIGURATION.getProfileDirectory() + "/web/",
					"jar:file:" + CONFIGURATION.getProfileDirectory() + "/web.zip!/",
					"file:" + CONFIGURATION.getWebPath() + "/"
				);
			}
		});


		// Setup the socket address
		InetSocketAddress address = new InetSocketAddress(InetAddress.getByName("0.0.0.0"), port);

		// Initialize the HTTP(S) server
		if (CONFIGURATION.getWebHttps()) {
			try {
				server = httpsServer(address);
			} catch (IOException e) {
				LOGGER.error("Failed to start web interface on HTTPS: {}", e.getMessage());
				LOGGER.trace("", e);
				if (e.getMessage().contains("UMS.jks")) {
					LOGGER.info(
						"To enable HTTPS please generate a self-signed keystore file " +
						"called \"UMS.jks\" with password \"umsums\" using the java " +
						"'keytool' commandline utility, and place it in the profile folder"
					);
				}
			} catch (GeneralSecurityException e) {
				LOGGER.error("Failed to start web interface on HTTPS due to a security error: {}", e.getMessage());
				LOGGER.trace("", e);
			}
		} else {
			server = HttpServer.create(address, 0);
		}

		if (server != null) {
			int threads = CONFIGURATION.getWebThreads();

			// Add context handlers
			addCtx("/", new RemoteStartHandler(this));
			addCtx("/browse", new RemoteBrowseHandler(this));
			RemotePlayHandler playHandler = new RemotePlayHandler(this);
			addCtx("/play", playHandler);
			addCtx("/playstatus", playHandler);
			addCtx("/playlist", playHandler);
			addCtx("/m3u8", playHandler);
			addCtx("/media", new RemoteMediaHandler(this));
			addCtx("/fmedia", new RemoteMediaHandler(this, true));
			addCtx("/thumb", new RemoteThumbHandler(this));
			addCtx("/raw", new RemoteRawHandler(this));
			addCtx("/files", new RemoteFileHandler(this));
			addCtx("/doc", new RemoteDocHandler(this));
			addCtx("/poll", new RemotePollHandler(this));
			server.setExecutor(Executors.newFixedThreadPool(threads));
			server.start();
		}
	}

	private HttpServer httpsServer(InetSocketAddress address) throws IOException, GeneralSecurityException {
		// Initialize the keystore
		char[] password = "umsums".toCharArray();
		keyStore = KeyStore.getInstance("JKS");
		try (FileInputStream fis = new FileInputStream(FileUtil.appendPathSeparator(CONFIGURATION.getProfileDirectory()) + "UMS.jks")) {
			keyStore.load(fis, password);
		}

		// Setup the key manager factory
		keyManagerFactory = KeyManagerFactory.getInstance("SunX509");
		keyManagerFactory.init(keyStore, password);

		// Setup the trust manager factory
		trustManagerFactory = TrustManagerFactory.getInstance("SunX509");
		trustManagerFactory.init(keyStore);

		HttpsServer httpsServer = HttpsServer.create(address, 0);
		sslContext = SSLContext.getInstance("TLS");
		sslContext.init(keyManagerFactory.getKeyManagers(), trustManagerFactory.getTrustManagers(), null);

		httpsServer.setHttpsConfigurator(new HttpsConfigurator(sslContext) {

			@Override
			public void configure(HttpsParameters params) {
				try {
					// initialise the SSL context
					SSLContext c = SSLContext.getDefault();
					SSLEngine engine = c.createSSLEngine();
					params.setNeedClientAuth(true);
					params.setCipherSuites(engine.getEnabledCipherSuites());
					params.setProtocols(engine.getEnabledProtocols());

					// get the default parameters
					SSLParameters defaultSSLParameters = c.getDefaultSSLParameters();
					params.setSSLParameters(defaultSSLParameters);
				} catch (Exception e) {
					LOGGER.debug("https configure error  " + e);
				}
			}
		});
		return httpsServer;
	}

	public String getTag(String user) {
		String tag = PMS.getCredTag("web", user);
		if (tag == null) {
			return user;
		}
		return tag;
	}

	public String getAddress() {
		return PMS.get().getServer().getHost() + ":" + server.getAddress().getPort();
	}

	public RootFolder getRoot(String user, HttpExchange t) throws InterruptedException {
		return getRoot(user, false, t);
	}

	public RootFolder getRoot(String user, boolean create, HttpExchange t) throws InterruptedException {
		String cookie = RemoteUtil.getCookie("UMS", t);
		RootFolder root;
		synchronized (roots) {
			root = roots.get(cookie);
			if (root == null) {
				// Double-check for cookie errors
				WebRender valid = RemoteUtil.matchRenderer(user, t);
				if (valid != null) {
					// A browser of the same type and user is already connected
					// at
					// this ip but for some reason we didn't get a cookie match.
					RootFolder validRoot = valid.getRootFolder();
					// Do a reverse lookup to see if it's been registered
					for (Map.Entry<String, RootFolder> entry : roots.entrySet()) {
						if (entry.getValue() == validRoot) {
							// Found
							root = validRoot;
							cookie = entry.getKey();
							LOGGER.debug("Allowing browser connection without cookie match: {}: {}", valid.getRendererName(),
								t.getRemoteAddress().getAddress());
							break;
						}
					}
				}
			}

			if (!create || (root != null)) {
				t.getResponseHeaders().add("Set-Cookie", "UMS=" + cookie + ";Path=/;SameSite=Strict");
				return root;
			}

			root = new RootFolder();
			try {
				WebRender render = new WebRender(user);
				root.setDefaultRenderer(render);
				render.setRootFolder(root);
				render.associateIP(t.getRemoteAddress().getAddress());
				render.associatePort(t.getRemoteAddress().getPort());
				if (CONFIGURATION.useWebSubLang()) {
					render.setSubLang(StringUtils.join(RemoteUtil.getLangs(t), ","));
				}
				render.setBrowserInfo(RemoteUtil.getCookie("UMSINFO", t), t.getRequestHeaders().getFirst("User-agent"));
				PMS.get().setRendererFound(render);
			} catch (ConfigurationException e) {
				root.setDefaultRenderer(RendererConfiguration.getDefaultConf());
			}

			root.discoverChildren();
			cookie = UUID.randomUUID().toString();
			t.getResponseHeaders().add("Set-Cookie", "UMS=" + cookie + ";Path=/;SameSite=Strict");
			roots.put(cookie, root);
		}
		return root;
	}

	public void associate(HttpExchange t, WebRender webRenderer) {
		webRenderer.associateIP(t.getRemoteAddress().getAddress());
		webRenderer.associatePort(t.getRemoteAddress().getPort());
	}

	private void addCtx(String path, HttpHandler h) {
		HttpContext ctx = server.createContext(path, h);
		if (CONFIGURATION.isWebAuthenticate()) {
			ctx.setAuthenticator(new BasicAuthenticator(CONFIGURATION.getServerName()) {
				@Override
				public boolean checkCredentials(String user, String pwd) {
					LOGGER.debug("authenticate " + user);
					return PMS.verifyCred("web", PMS.getCredTag("web", user), user, pwd);
				}
			});
		}
	}

	public HttpServer getServer() {
		return server;
	}

	static class RemoteThumbHandler implements HttpHandler {
		private RemoteWeb parent;

		public RemoteThumbHandler(RemoteWeb parent) {
			this.parent = parent;
		}

		@Override
		public void handle(HttpExchange t) throws IOException {
			try {
				if (RemoteUtil.deny(t)) {
					throw new IOException("Access denied");
				}
				String id = RemoteUtil.getId("thumb/", t);
				LOGGER.trace("web thumb req " + id);
				if (id.contains("logo")) {
					RemoteUtil.sendLogo(t);
					return;
				}

				RootFolder root = parent.getRoot(RemoteUtil.userName(t), t);
				if (root == null) {
					LOGGER.debug("weird root in thumb req");
					throw new IOException("Unknown root");
				}

				final DLNAResource r = root.getDLNAResource(id, root.getDefaultRenderer());
				if (r == null) {
					// another error
					LOGGER.debug("media unknown");
					throw new IOException("Bad id");
				}

				DLNAThumbnailInputStream in;
				if (!CONFIGURATION.isShowCodeThumbs() && !r.isCodeValid(r)) {
					// we shouldn't show the thumbs for coded objects
					// unless the code is entered
					in = r.getGenericThumbnailInputStream(null);
				} else {
					r.checkThumbnail();
					in = r.fetchThumbnailInputStream();
					if (in == null) {
						// if r is null for some reason, default to generic thumb
						in = r.getGenericThumbnailInputStream(null);
					}
				}

				BufferedImageFilterChain filterChain = null;
				if (
					(
						r instanceof RealFile &&
						FullyPlayed.isFullyPlayedFileMark(((RealFile) r).getFile())
					) ||
					(
						r instanceof MediaLibraryFolder &&
						((MediaLibraryFolder) r).isTVSeries() &&
						FullyPlayed.isFullyPlayedTVSeriesMark(((MediaLibraryFolder) r).getName())
					)
				) {
					filterChain = new BufferedImageFilterChain(FullyPlayed.getOverlayFilter());
				}
				filterChain = r.addFlagFilters(filterChain);
				if (filterChain != null) {
					in = in.transcode(in.getDLNAImageProfile(), false, filterChain);
				}
				Headers hdr = t.getResponseHeaders();
				hdr.add("Content-Type", ImageFormat.PNG.equals(in.getFormat()) ? HTTPResource.PNG_TYPEMIME : HTTPResource.JPEG_TYPEMIME);
				hdr.add("Accept-Ranges", "bytes");
				hdr.add("Connection", "keep-alive");
				t.sendResponseHeaders(200, in.getSize());
				OutputStream os = t.getResponseBody();
				LOGGER.trace("Web thumbnail: Input is {} output is {}", in, os);
				RemoteUtil.dump(in, os);
			} catch (IOException e) {
				throw e;
			} catch (Exception e) {
				// Nothing should get here, this is just to avoid crashing the thread
				LOGGER.error("Unexpected error in RemoteThumbHandler.handle(): {}", e.getMessage());
				LOGGER.trace("", e);
			}
		}
	}

	static class RemoteFileHandler implements HttpHandler {
		private RemoteWeb parent;

		public RemoteFileHandler(RemoteWeb parent) {
			this.parent = parent;
		}

		@Override
		public void handle(HttpExchange t) throws IOException {
			try {
				LOGGER.debug("Handling web interface file request \"{}\"", t.getRequestURI());

				String path = t.getRequestURI().getPath();
				String response = null;
				String mime = null;
				int status = 200;

				if (path.contains("crossdomain.xml")) {
					response = "<?xml version=\"1.0\"?>" +
						"<!-- http://www.bitsontherun.com/crossdomain.xml -->" +
						"<cross-domain-policy>" +
						"<allow-access-from domain=\"*\" />" +
						"</cross-domain-policy>";
					mime = "text/xml";

				} else if (path.startsWith("/files/log/")) {
					String filename = path.substring(11);
					if (filename.equals("info")) {
						String log = PMS.get().getFrame().getLog();
						log = log.replaceAll("\n", "<br>");
						String fullLink = "<br><a href=\"/files/log/full\">Full log</a><br><br>";
						String x = fullLink + log;
						if (StringUtils.isNotEmpty(log)) {
							x = x + fullLink;
						}
						response = "<html><title>UMS LOG</title><body>" + x + "</body></html>";
					} else {
						File file = parent.getResources().getFile(filename);
						if (file != null) {
							filename = file.getName();
							HashMap<String, Object> vars = new HashMap<>();
							vars.put("title", filename);
							vars.put("brush", filename.endsWith("debug.log") ? "debug_log" : filename.endsWith(".log") ? "log" : "conf");
							vars.put("log", RemoteUtil.read(file).replace("<", "&lt;"));
							response = parent.getResources().getTemplate("util/log.html").execute(vars);
						} else {
							status = 404;
						}
					}
					mime = "text/html";
				} else if (path.startsWith("/files/proxy")) {
					String url = t.getRequestURI().getQuery();
					if (url != null) {
						url = url.substring(2);
					}

					InputStream in = null;
					CookieManager cookieManager = (CookieManager) CookieHandler.getDefault();
					if (cookieManager == null) {
						cookieManager = new CookieManager();
						CookieHandler.setDefault(cookieManager);
					}

					if (t.getRequestMethod().equals("POST")) {
						ByteArrayOutputStream bytes = new ByteArrayOutputStream();
						byte[] buf = new byte[4096];
						int n;
						while ((n = t.getRequestBody().read(buf)) > -1) {
							bytes.write(buf, 0, n);
						}
						String str = bytes.toString("utf-8");

						URLConnection conn = new URL(url).openConnection();
						((HttpURLConnection) conn).setRequestMethod("POST");
						conn.setRequestProperty("Content-type", t.getRequestHeaders().getFirst("Content-type"));
						conn.setRequestProperty("Content-Length", String.valueOf(str.length()));
						conn.setDoOutput(true);
						OutputStreamWriter writer = new OutputStreamWriter(conn.getOutputStream());
						writer.write(str);
						writer.flush();

						in = conn.getInputStream();
						bytes = new ByteArrayOutputStream();
						while ((n = in.read(buf)) > -1) {
							bytes.write(buf, 0, n);
						}
						in = new ByteArrayInputStream(bytes.toByteArray());

						if (LOGGER.isDebugEnabled()) {
							List<HttpCookie> cookies = cookieManager.getCookieStore().getCookies();
							for (HttpCookie cookie : cookies) {
								LOGGER.debug("Domain: {}, Cookie: {}", cookie.getDomain(), cookie);
							}
						}
					} else if (t.getRequestMethod().equals("OPTIONS")) {
						in = new ByteArrayInputStream("".getBytes(StandardCharsets.UTF_8));
					} else {
						in = HTTPResource.downloadAndSend(url, false);

						if (LOGGER.isDebugEnabled()) {
							List<HttpCookie> cookies = cookieManager.getCookieStore().getCookies();
							for (HttpCookie cookie : cookies) {
								LOGGER.debug("Domain: {}, Cookie: {}", cookie.getDomain(), cookie);
							}
						}
					}
					Headers hdr = t.getResponseHeaders();
					hdr.add("Content-Type", "text/plain");
					hdr.add("Access-Control-Allow-Origin", "*");
					hdr.add("Access-Control-Allow-Headers", "User-Agent");
					hdr.add("Access-Control-Allow-Headers", "Content-Type");
					t.sendResponseHeaders(200, in.available());

					OutputStream os = t.getResponseBody();
					LOGGER.trace("input is {} output is {}", in, os);
					RemoteUtil.dump(in, os);
					return;
				} else if (parent.getResources().write(path.substring(7), t)) {
					// The resource manager found and sent the file, all done.
					return;

				} else {
					status = 404;
				}

				if (status == 404 && response == null) {
					response = "<html><body>404 - File Not Found: " + path + "</body></html>";
					mime = "text/html";
				}

				RemoteUtil.respond(t, response, status, mime);
			} catch (IOException e) {
				throw e;
			} catch (Exception e) {
				// Nothing should get here, this is just to avoid crashing the
				// thread
				LOGGER.error("Unexpected error in RemoteFileHandler.handle(): {}", e.getMessage());
				LOGGER.trace("", e);
			}
		}
	}

	static class RemoteStartHandler implements HttpHandler {
		private static final Logger LOGGER = LoggerFactory.getLogger(RemoteStartHandler.class);
		@SuppressWarnings("unused")
		private final static String CRLF = "\r\n";
		private RemoteWeb parent;

		public RemoteStartHandler(RemoteWeb parent) {
			this.parent = parent;
		}

		@Override
		public void handle(HttpExchange t) throws IOException {
			try {
				LOGGER.debug("root req " + t.getRequestURI());
				if (RemoteUtil.deny(t)) {
					throw new IOException("Access denied");
				}
				if (t.getRequestURI().getPath().contains("favicon")) {
					RemoteUtil.sendLogo(t);
					return;
				}

				HashMap<String, Object> vars = new HashMap<>();
				vars.put("serverName", CONFIGURATION.getServerDisplayName());

				try {
					Template template = parent.getResources().getTemplate("start.html");
					if (template != null) {
						String response = template.execute(vars);
						RemoteUtil.respond(t, response, 200, "text/html");
					} else {
						throw new IOException("Web template \"start.html\" not found");
					}
				} catch (MustacheException e) {
					LOGGER.error("An error occurred while generating a HTTP response: {}", e.getMessage());
					LOGGER.trace("", e);
				}
			} catch (IOException e) {
				throw e;
			} catch (Exception e) {
				// Nothing should get here, this is just to avoid crashing the thread
				LOGGER.error("Unexpected error in RemoteStartHandler.handle(): {}", e.getMessage());
				LOGGER.trace("", e);
			}
		}
	}

	static class RemoteDocHandler implements HttpHandler {
		private static final Logger LOGGER = LoggerFactory.getLogger(RemoteDocHandler.class);
		@SuppressWarnings("unused")
		private final static String CRLF = "\r\n";

		private RemoteWeb parent;

		public RemoteDocHandler(RemoteWeb parent) {
			this.parent = parent;
			// Make sure logs are available right away
			getLogs(false);
		}

		@Override
		public void handle(HttpExchange t) throws IOException {
			try {
				LOGGER.debug("root req " + t.getRequestURI());
				if (RemoteUtil.deny(t)) {
					throw new IOException("Access denied");
				}
				if (t.getRequestURI().getPath().contains("favicon")) {
					RemoteUtil.sendLogo(t);
					return;
				}

				HashMap<String, Object> vars = new HashMap<>();
				vars.put("logs", getLogs(true));
				if (CONFIGURATION.getUseCache()) {
					vars.put("cache",
						"http://" + PMS.get().getServer().getHost() + ":" + PMS.get().getServer().getPort() + "/console/home");
				}

				String response = parent.getResources().getTemplate("doc.html").execute(vars);
				RemoteUtil.respond(t, response, 200, "text/html");
			} catch (IOException e) {
				throw e;
			} catch (Exception e) {
				// Nothing should get here, this is just to avoid crashing the thread
				LOGGER.error("Unexpected error in RemoteDocHandler.handle(): {}", e.getMessage());
				LOGGER.trace("", e);
			}
		}

		private ArrayList<HashMap<String, String>> getLogs(boolean asList) {
			Set<File> files = new DbgPacker().getItems();
			if (!asList) {
				return null;
			}
			ArrayList<HashMap<String, String>> logs = new ArrayList<HashMap<String, String>>();
			for (File f : files) {
				if (f.exists()) {
					String id = String.valueOf(parent.getResources().add(f));
					if (asList) {
						HashMap<String, String> item = new HashMap<>();
						item.put("filename", f.getName());
						item.put("id", id);
						logs.add(item);
					}
				}
			}
			return logs;
		}
	}

	public RemoteUtil.ResourceManager getResources() {
		return resources;
	}

	public String getUrl() {
		if (server != null) {
			return (server instanceof HttpsServer ?
					"https://" :
					"http://") + PMS.get().getServer().getHost() + ":" + server.getAddress().getPort();
		}
		return null;
	}

	static class RemotePollHandler implements HttpHandler {
		private static final Logger LOGGER = LoggerFactory.getLogger(RemotePollHandler.class);
		@SuppressWarnings("unused")
		private final static String CRLF = "\r\n";

		private RemoteWeb parent;

		public RemotePollHandler(RemoteWeb parent) {
			this.parent = parent;
		}

		@Override
		public void handle(HttpExchange t) throws IOException {
			try {
				// LOGGER.debug("poll req " + t.getRequestURI());
				if (RemoteUtil.deny(t)) {
					throw new IOException("Access denied");
				}
				RootFolder root = parent.getRoot(RemoteUtil.userName(t), t);
				WebRender renderer = (WebRender) root.getDefaultRenderer();
				String json = renderer.getPushData();
				RemoteUtil.respond(t, json, 200, "application/json");
			} catch (IOException e) {
				throw e;
			} catch (Exception e) {
				// This can happen if a browser is left open and our cookie changed, or something like that
				// I'm just leaving this note here as a clue for the next person who encounters this
				LOGGER.error("Unexpected error on web interface. Please try closing any tabs or windows that contain UMS and try again");
				LOGGER.debug("", e);
			}
		}
	}
}
