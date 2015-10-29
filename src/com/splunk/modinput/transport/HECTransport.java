package com.splunk.modinput.transport;

import java.net.URI;
import java.security.cert.X509Certificate;
import java.util.Collections;
import java.util.LinkedList;
import java.util.List;
import java.util.concurrent.Future;

import javax.net.ssl.HostnameVerifier;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSession;

import org.apache.http.HttpHost;
import org.apache.http.HttpResponse;

import org.apache.http.client.methods.HttpPost;
import org.apache.http.client.utils.URIBuilder;
import org.apache.http.config.Registry;
import org.apache.http.config.RegistryBuilder;
import org.apache.http.conn.routing.HttpRoute;
import org.apache.http.entity.ContentType;
import org.apache.http.entity.StringEntity;
import org.apache.http.impl.nio.client.CloseableHttpAsyncClient;
import org.apache.http.impl.nio.client.HttpAsyncClients;
import org.apache.http.impl.nio.conn.PoolingNHttpClientConnectionManager;
import org.apache.http.impl.nio.reactor.DefaultConnectingIOReactor;
import org.apache.http.nio.conn.NoopIOSessionStrategy;
import org.apache.http.nio.conn.SchemeIOSessionStrategy;
import org.apache.http.nio.conn.ssl.SSLIOSessionStrategy;
import org.apache.http.nio.reactor.ConnectingIOReactor;
import org.apache.http.ssl.SSLContexts;
import org.apache.http.ssl.TrustStrategy;
import org.apache.http.util.EntityUtils;
import org.apache.log4j.Logger;

import org.json.JSONObject;
import org.json.JSONException;

import com.splunk.modinput.ModularInput;

public class HECTransport implements Transport {

	protected static Logger logger = Logger.getLogger(HECTransport.class);

	private HECTransportConfig config;
	private CloseableHttpAsyncClient httpClient;
	private URI uri;

	// batch buffer
	private List<String> batchBuffer;
	private long currentBatchSizeBytes = 0;
	private long lastEventReceivedTime;

	private static final HostnameVerifier HOSTNAME_VERIFIER = new HostnameVerifier() {
		public boolean verify(String s, SSLSession sslSession) {
			return true;
		}
	};

	@Override
	public void init(Object obj) {
		config = (HECTransportConfig) obj;

		this.batchBuffer = Collections
				.synchronizedList(new LinkedList<String>());
		this.lastEventReceivedTime = System.currentTimeMillis();

		try {

			Registry<SchemeIOSessionStrategy> sslSessionStrategy = RegistryBuilder
					.<SchemeIOSessionStrategy> create()
					.register("http", NoopIOSessionStrategy.INSTANCE)
					.register(
							"https",
							new SSLIOSessionStrategy(getSSLContext(),
									HOSTNAME_VERIFIER)).build();

			ConnectingIOReactor ioReactor = new DefaultConnectingIOReactor();
			PoolingNHttpClientConnectionManager cm = new PoolingNHttpClientConnectionManager(
					ioReactor, sslSessionStrategy);
			cm.setMaxTotal(config.getPoolsize());

			HttpHost splunk = new HttpHost(config.getHost(), config.getPort());
			cm.setMaxPerRoute(new HttpRoute(splunk), config.getPoolsize());

			httpClient = HttpAsyncClients.custom().setConnectionManager(cm)
					.build();

			uri = new URIBuilder()
					.setScheme(config.isHttps() ? "https" : "http")
					.setHost(config.getHost()).setPort(config.getPort())
					.setPath("/services/collector").build();

			httpClient.start();

			if (config.isBatchMode()) {
				new BatchBufferActivityCheckerThread().start();
			}

		} catch (Exception e) {
			logger.error("Error initialising HEC Transport: "
					+ ModularInput.getStackTrace(e));
		}

	}

	class BatchBufferActivityCheckerThread extends Thread {

		BatchBufferActivityCheckerThread() {

		}

		public void run() {

			while (true) {
				String currentMessage = "";
				try {
					long currentTime = System.currentTimeMillis();
					if ((currentTime - lastEventReceivedTime) >= config
							.getMaxInactiveTimeBeforeBatchFlush()) {
						if (batchBuffer.size() > 0) {
							currentMessage = rollOutBatchBuffer();
							batchBuffer.clear();
							currentBatchSizeBytes = 0;
							hecPost(currentMessage);
						}
					}

					Thread.sleep(1000);
				} catch (Exception e) {

				}

			}
		}
	}

	private SSLContext getSSLContext() {
		TrustStrategy acceptingTrustStrategy = new TrustStrategy() {
			public boolean isTrusted(X509Certificate[] certificate,
					String authType) {
				return true;
			}
		};
		SSLContext sslContext = null;
		try {
			sslContext = SSLContexts.custom()
					.loadTrustMaterial(null, acceptingTrustStrategy).build();
		} catch (Exception e) {
			// Handle error
		}
		return sslContext;

	}

	@Override
	public void setStanzaName(String name) {
		// not required

	}

	private void createAndSendHECEvent(String message, String time, String host, String source) {
		String currentMessage = "";
		JSONObject jsonObject = null;
		try{
			jsonObject = new JSONObject();

			jsonObject.put("event", typedMessage(message));

			if (!config.getIndex().equalsIgnoreCase("default")) {
				jsonObject.put("index", config.getIndex());
			}

			if (source == null || source.length() == 0) {
				source = config.getSource();
			}
			jsonObject.put("source", source);

			if (host != null && host.length() > 0) {
				jsonObject.put("host", host);
			}

			if (time != null && time.length() > 0) {
				jsonObject.put("time", time);
			}

			jsonObject.put("sourcetype", config.getSourcetype());

			currentMessage = jsonObject.toString();

			if (config.isBatchMode()) {
				lastEventReceivedTime = System.currentTimeMillis();
				currentBatchSizeBytes += currentMessage.length();
				batchBuffer.add(currentMessage);
				if (flushBuffer()) {
					currentMessage = rollOutBatchBuffer();
					batchBuffer.clear();
					currentBatchSizeBytes = 0;
					hecPost(currentMessage);
				}
			} else {
				hecPost(currentMessage);
			}

		} catch (Exception e) {
			logger.error("Error writing received data via HEC: "
					+ ModularInput.getStackTrace(e));
		}

	}

	@Override
	public void transport(String message, String time, String host, String source) {

		createAndSendHECEvent(message, time, host, source);
	}

	@Override
	public void transport(String message) {

		createAndSendHECEvent(message, "", "", "");
	}

	private boolean flushBuffer() {

		return (currentBatchSizeBytes >= config.getMaxBatchSizeBytes())
				|| (batchBuffer.size() >= config.getMaxBatchSizeEvents());

	}

	private String rollOutBatchBuffer() {

		StringBuffer sb = new StringBuffer();

		for (String event : batchBuffer) {
			sb.append(event);
		}

		return sb.toString();
	}

	private void hecPost(String currentMessage) throws Exception {

		HttpPost post = new HttpPost(uri);
		post.addHeader("Authorization", "Splunk " + config.getToken());

		StringEntity requestEntity = new StringEntity(currentMessage,
				ContentType.create("application/json", "UTF-8"));

		post.setEntity(requestEntity);
		Future<HttpResponse> future = httpClient.execute(post, null);
		HttpResponse response = future.get();
		int code = response.getStatusLine().getStatusCode();
		if (code != 200) {
			logger.error("Error sending HEC event , "
					+ response.getStatusLine() + " , " + EntityUtils.toString(response.getEntity()));
		}

	}

	private Object typedMessage(String message) {
		String trimmedMessage = message.trim();

		// Check for JSON object
		if (trimmedMessage.startsWith("{") && trimmedMessage.endsWith("}")) {
			// this is *probably* JSON.
			try {
				return new JSONObject(trimmedMessage);
			} catch (JSONException e) {
				// guess not really JSON.  Fall through to backup code
			}
		}

		// Treating message as a string
		return message;
	}
}
