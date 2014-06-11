package strat.mining.stratum.proxy.worker;

import java.io.BufferedReader;
import java.io.IOException;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.nio.charset.Charset;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import org.glassfish.grizzly.CompletionHandler;
import org.glassfish.grizzly.http.server.HttpHandler;
import org.glassfish.grizzly.http.server.Request;
import org.glassfish.grizzly.http.server.Response;
import org.glassfish.grizzly.http.util.Header;
import org.glassfish.grizzly.http.util.HttpStatus;
import org.glassfish.jersey.internal.util.Base64;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import strat.mining.stratum.proxy.callback.LongPollingCallback;
import strat.mining.stratum.proxy.constant.Constants;
import strat.mining.stratum.proxy.exception.AuthorizationException;
import strat.mining.stratum.proxy.exception.ChangeExtranonceNotSupportedException;
import strat.mining.stratum.proxy.exception.NoCredentialsException;
import strat.mining.stratum.proxy.exception.NoPoolAvailableException;
import strat.mining.stratum.proxy.exception.TooManyWorkersException;
import strat.mining.stratum.proxy.json.GetworkRequest;
import strat.mining.stratum.proxy.json.GetworkResponse;
import strat.mining.stratum.proxy.json.MiningAuthorizeRequest;
import strat.mining.stratum.proxy.json.MiningSubmitResponse;
import strat.mining.stratum.proxy.json.MiningSubscribeRequest;
import strat.mining.stratum.proxy.manager.StratumProxyManager;
import strat.mining.stratum.proxy.pool.Pool;

import com.fasterxml.jackson.core.JsonParseException;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonMappingException;
import com.fasterxml.jackson.databind.ObjectMapper;

public class GetworkRequestHandler extends HttpHandler {

	private static final Logger LOGGER = LoggerFactory.getLogger(GetworkRequestHandler.class);

	private StratumProxyManager manager;

	private static ObjectMapper jsonUnmarshaller = new ObjectMapper();;

	private Map<InetAddress, GetworkWorkerConnection> workerConnections;

	public GetworkRequestHandler(StratumProxyManager manager) {
		this.manager = manager;
		this.workerConnections = Collections.synchronizedMap(new HashMap<InetAddress, GetworkWorkerConnection>());

	}

	@Override
	public void service(final Request request, final Response response) throws Exception {
		response.setHeader("X-Mining-Extensions", "longpoll");

		String content = null;
		try {
			content = new BufferedReader(request.getReader()).readLine();
			LOGGER.trace("New request from {}: {}", request.getRemoteAddr(), content);

			setRequestCredentials(request);

			final GetworkWorkerConnection workerConnection = getWorkerConnection(request);
			final GetworkRequest getworkRequest = jsonUnmarshaller.readValue(content, GetworkRequest.class);

			if (!request.getRequestURI().equalsIgnoreCase(Constants.DEFAULT_GETWORK_LONG_POLLING_URL)) {
				// Basic getwork Request
				response.setHeader("X-Long-Polling", Constants.DEFAULT_GETWORK_LONG_POLLING_URL);

				if (getworkRequest.getData() != null) {
					// If data are presents, it is a submit request
					LOGGER.debug("New getwork submit request from user {}@{}.", request.getAttribute("username"),
							workerConnection.getConnectionName());
					processGetworkSubmit(request, response, workerConnection, getworkRequest);
				} else {
					// Else it is a getwork request
					LOGGER.debug("New getwork request from user {}@{}.", request.getAttribute("username"), workerConnection.getConnectionName());
					processGetworkRequest(request, response, workerConnection, getworkRequest);
				}

			} else {
				// Long polling getwork request
				LOGGER.debug("New getwork long-polling request from user {}@{}.", request.getAttribute("username"),
						workerConnection.getConnectionName());
				processLongPollingRequest(request, response, workerConnection, getworkRequest);
			}

		} catch (NoCredentialsException e) {
			LOGGER.warn("Request from {} without credentials. Returning 401 Unauthorized.", request.getRemoteAddr());
			response.setHeader(Header.WWWAuthenticate, "Basic realm=\"stratum-proxy\"");
			response.setStatus(HttpStatus.UNAUTHORIZED_401);
		} catch (AuthorizationException e) {
			LOGGER.warn("Authorization failed for getwork request from {}. {}", request.getRemoteAddr(), e.getMessage());
		} catch (JsonParseException | JsonMappingException e) {
			LOGGER.error("Unsupported request content from {}: {}", request.getRemoteAddr(), content, e);
		}

	}

	/**
	 * Process the request as a long-polling one.
	 * 
	 * @param request
	 * @param response
	 * @param workerConnection
	 * @param getworkRequest
	 */
	private void processLongPollingRequest(final Request request, final Response response, final GetworkWorkerConnection workerConnection,
			final GetworkRequest getworkRequest) {
		// Prepare the callback of long polling
		final LongPollingCallback longPollingCallback = new LongPollingCallback() {
			public void onLongPollingOver() {
				try {
					// Once the worker connection call the callback, process the
					// getwork request to fill the response.
					processGetworkRequest(request, response, workerConnection, getworkRequest);

					// Then resume the response to send it to the miner.
					response.resume();
				} catch (Exception e) {
					LOGGER.error("Failed to send  long-polling response to {}@{}.", request.getAttribute("username"),
							workerConnection.getConnectionName(), e);
				}
			}
		};

		// Suspend the response for at least 70 seconds (miners should cancel
		// the request after 60 seconds)
		// If the request is cancelled or failed, remove hte callback from the
		// worker connection since there is no need to call it.
		response.suspend(70, TimeUnit.SECONDS, new CompletionHandler<Response>() {
			public void updated(Response result) {
			}

			public void failed(Throwable throwable) {
				LOGGER.error("Long-polling request of {}@{} failed. Cause: {}", request.getAttribute("username"),
						workerConnection.getConnectionName(), throwable.getMessage());
				workerConnection.removeLongPollingCallback(longPollingCallback);
			}

			public void completed(Response result) {
			}

			public void cancelled() {
				LOGGER.error("Long-polling request of {}@{} cancelled.", request.getAttribute("username"), workerConnection.getConnectionName());
				workerConnection.removeLongPollingCallback(longPollingCallback);
			}
		});

		// Add the callback to the worker connection
		workerConnection.addLongPollingCallback(longPollingCallback);
	}

	/**
	 * Process a basic (non long-polling) getwork request.
	 * 
	 * @param request
	 * @param response
	 * @param workerConnection
	 * @param getworkRequest
	 * @throws JsonProcessingException
	 * @throws IOException
	 */
	protected void processGetworkRequest(Request request, Response response, GetworkWorkerConnection workerConnection, GetworkRequest getworkRequest)
			throws JsonProcessingException, IOException {
		// Return the getwork data
		GetworkResponse jsonResponse = new GetworkResponse();
		jsonResponse.setId(getworkRequest.getId());
		jsonResponse.setData(workerConnection.getGetworkData());
		jsonResponse.setTarget(workerConnection.getGetworkTarget());

		String result = jsonUnmarshaller.writeValueAsString(jsonResponse);
		LOGGER.debug("Returning response to {}@{}: {}", request.getAttribute("username"), request.getRemoteAddr(), result);
		response.getOutputBuffer().write(result);
	}

	/**
	 * Process a getwork share submission.
	 * 
	 * @param request
	 * @param response
	 * @param workerConnection
	 * @param getworkRequest
	 * @throws JsonProcessingException
	 * @throws IOException
	 */
	protected void processGetworkSubmit(Request request, Response response, GetworkWorkerConnection workerConnection, GetworkRequest getworkRequest)
			throws JsonProcessingException, IOException {
		MiningSubmitResponse jsonResponse = new MiningSubmitResponse();
		jsonResponse.setId(getworkRequest.getId());

		String errorMesage = workerConnection.submitWork((String) request.getAttribute("username"), getworkRequest.getData());
		// If there is an error message, the share submit has
		// failed/been rejected
		if (errorMesage != null) {
			response.setHeader("X-Reject-Reason", errorMesage);
			jsonResponse.setIsAccepted(false);
		} else {
			jsonResponse.setIsAccepted(true);
		}

		String result = jsonUnmarshaller.writeValueAsString(jsonResponse);
		LOGGER.debug("Returning response to {}@{}: {}", request.getAttribute("username"), request.getRemoteAddr(), result);
		response.getOutputBuffer().write(result);
	}

	/**
	 * Check if the request is authorized. If not authorized, throw an
	 * exception. The response status code and headers are modified.
	 * 
	 * @param request
	 * @throws AuthorizationException
	 * @throws NoCredentialsException
	 */
	private void checkAuthorization(GetworkWorkerConnection connection, Request request) throws AuthorizationException, NoCredentialsException {
		MiningAuthorizeRequest authorizeRequest = new MiningAuthorizeRequest();
		authorizeRequest.setUsername((String) request.getAttribute("username"));
		authorizeRequest.setPassword((String) request.getAttribute("password"));
		manager.onAuthorizeRequest(connection, authorizeRequest);
	}

	/**
	 * Return the worker connection of the request.
	 * 
	 * @param request
	 * @return
	 * @throws ChangeExtranonceNotSupportedException
	 * @throws TooManyWorkersException
	 * @throws NoCredentialsException
	 * @throws AuthorizationException
	 */
	private GetworkWorkerConnection getWorkerConnection(Request request) throws UnknownHostException, NoPoolAvailableException,
			TooManyWorkersException, ChangeExtranonceNotSupportedException, NoCredentialsException, AuthorizationException {
		InetAddress address = InetAddress.getByName(request.getRemoteAddr());

		GetworkWorkerConnection workerConnection = workerConnections.get(address);
		if (workerConnection == null) {
			LOGGER.debug("No existing getwork connections for address {}.", request.getRemoteAddr());
			workerConnection = new GetworkWorkerConnection(address, manager);

			MiningSubscribeRequest subscribeRequest = new MiningSubscribeRequest();
			Pool pool = manager.onSubscribeRequest(workerConnection, subscribeRequest);
			workerConnection.rebindToPool(pool);

			workerConnections.put(address, workerConnection);
		}

		try {
			checkAuthorization(workerConnection, request);
			workerConnection.addAuthorizedUsername((String) request.getAttribute("username"));
		} catch (AuthorizationException e) {
			workerConnections.remove(address);
			manager.onWorkerDisconnection(workerConnection, e);
			throw e;
		}

		return workerConnection;
	}

	/**
	 * Set the username/password of the request in the request attributes if
	 * they exist. Else, throw an exception.
	 * 
	 * @param request
	 * @return
	 */
	private void setRequestCredentials(Request request) throws NoCredentialsException {
		String authorization = request.getAuthorization();
		if (authorization != null && authorization.startsWith("Basic")) {
			// Authorization: Basic base64credentials
			String base64Credentials = authorization.substring("Basic".length()).trim();
			String credentialsString = new String(Base64.decode(base64Credentials.getBytes()), Charset.forName("UTF-8"));
			// credentials = username:password
			String[] values = credentialsString.split(":", 2);

			request.setAttribute("username", values[0]);
			request.setAttribute("password", values[1]);

		} else {
			throw new NoCredentialsException();
		}
	}

}
