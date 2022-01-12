import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.ProxySelector;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import javax.servlet.AsyncContext;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import org.apache.commons.lang3.StringUtils;
import org.apache.hc.client5.http.classic.methods.HttpGet;
import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.apache.hc.client5.http.impl.classic.CloseableHttpResponse;
import org.apache.hc.client5.http.impl.classic.HttpClients;
import org.apache.hc.client5.http.impl.routing.SystemDefaultRoutePlanner;
import org.apache.hc.core5.http.ClassicHttpRequest;
import org.apache.hc.core5.http.io.entity.EntityUtils;
import org.eclipse.jetty.http.HttpHeader;
import org.eclipse.jetty.http.HttpHeaderValue;
import org.eclipse.jetty.proxy.ConnectHandler;
import org.eclipse.jetty.server.Connector;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.ServerConnector;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

public class ProxyTest {
    private final AtomicReference<String> errorBody = new AtomicReference<>("test");
    private final AtomicBoolean errorSendConnectionClose = new AtomicBoolean(true);

    private Server server;
    private CloseableHttpClient client;

    @BeforeEach
    void beforeEach() throws Exception {
        server = new Server();
        ServerConnector connector = new ServerConnector(server);
        connector.setHost("localhost");
        connector.setPort(8888);
        server.setConnectors(new Connector[] {connector});
        server.setHandler(new ErrorHasBodyConnectHandler());
        server.start();
        client = HttpClients.custom()
                .setRoutePlanner(new SystemDefaultRoutePlanner(
                        ProxySelector.of(new InetSocketAddress("localhost", 8888))))
                .build();
    }

    @AfterEach
    void afterEach() throws Exception {
        client.close();
        server.stop();
    }

    private void doRequest(ClassicHttpRequest request) throws Exception {
        try (CloseableHttpResponse response = client.execute(request)) {
            System.out.println(response.getCode() + " " + response.getReasonPhrase());
            System.out.println(EntityUtils.toString(response.getEntity()));
        }
    }

    @Test
    void testHttpclient2195Working() throws Exception {
        doRequest(new HttpGet("https://httpbin.org/get"));
    }

    @Test
    void testHttpclient2195BadPath404() throws Exception {
        doRequest(new HttpGet("https://httpbin.org/not-a-valid-path"));
    }

    @Test
    void testHttpclient2195BadHost() throws Exception {
        doRequest(new HttpGet("https://not-a-valid-host/get"));
    }

    @Test
    void testHttpclient2195BadHostNoConnectionClose() throws Exception {
        errorSendConnectionClose.set(false);
        doRequest(new HttpGet("https://not-a-valid-host/get"));
    }

    @Test
    void testHttpclient2195BadHostNoConnectionCloseZeroLength() throws Exception {
        errorBody.set("");
        errorSendConnectionClose.set(false);
        doRequest(new HttpGet("https://not-a-valid-host/get"));
    }

    @Test
    void testHttpclient2195BadHostLargeBody() throws Exception {
        errorBody.set(StringUtils.repeat("very long string", 1000));
        doRequest(new HttpGet("https://not-a-valid-host/get"));
    }

    // Copy ConnectHandler with modifications
    private final class ErrorHasBodyConnectHandler extends ConnectHandler {
        @Override
        protected void onConnectFailure(HttpServletRequest request, HttpServletResponse response, AsyncContext asyncContext, Throwable failure) {
            if (LOG.isDebugEnabled())
                LOG.debug("CONNECT failed", failure);
            sendConnectResponse(request, response, HttpServletResponse.SC_INTERNAL_SERVER_ERROR);
            if (asyncContext != null)
                asyncContext.complete();
        }

        private void sendConnectResponse(HttpServletRequest request, HttpServletResponse response, int statusCode)
        {
            try
            {
                response.setStatus(statusCode);
                // Modification: Conditionally send Connection: close
                if (statusCode != HttpServletResponse.SC_OK && errorSendConnectionClose.get())
                    response.setHeader(HttpHeader.CONNECTION.asString(), HttpHeaderValue.CLOSE.asString());
                // Modification: Send body
                byte[] content = errorBody.get().getBytes(StandardCharsets.UTF_8);
                response.setContentLength(content.length);
                try (OutputStream stream = response.getOutputStream()) {
                    stream.write(content);
                }
                if (LOG.isDebugEnabled())
                    LOG.debug("CONNECT response sent {} {}", request.getProtocol(), response.getStatus());
            }
            catch (IOException x)
            {
                if (LOG.isDebugEnabled())
                    LOG.debug("Could not send CONNECT response", x);
            }
        }
    }
}
