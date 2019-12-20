package io.supertokens.session;

import android.app.Application;

import java.io.IOException;
import java.net.CookieManager;
import java.net.HttpURLConnection;
import java.net.URISyntaxException;
import java.net.URL;
import java.util.Map;
import java.util.concurrent.locks.ReentrantReadWriteLock;

@SuppressWarnings("unused")
public class SuperTokensHttpURLConnection {
    private static final Object refreshTokenLock = new Object();
    private static final ReentrantReadWriteLock refreshAPILock = new ReentrantReadWriteLock();

    public static <T> T newRequest(URL url, SuperTokensHttpURLConnectionCallback<T> callback) throws IllegalAccessException, IOException, URISyntaxException {
        if ( !SuperTokens.isInitCalled ) {
            throw new IllegalAccessException("SuperTokens.init function needs to be called before using newRequest");
        }

        Application applicationContext = SuperTokens.contextWeakReference.get();
        if ( applicationContext == null ) {
            throw new IllegalAccessException("Application context is null");
        }
        try {
            while (true) {
                HttpURLConnection connection = null;
                try {
                    String preRequestIdRefreshToken;
                    T output;
                    int responseCode;
                    // TODO: write comment as to why we have this lock here. Do we also have this lock for iOS and website package?
                    refreshAPILock.readLock().lock();
                    try {
                        connection = (HttpURLConnection) url.openConnection();

                        // Add antiCSRF token, if present in storage, to the request headers
                        preRequestIdRefreshToken = IdRefreshToken.getToken(applicationContext);
                        String antiCSRFToken = AntiCSRF.getToken(applicationContext, preRequestIdRefreshToken);

                        if (antiCSRFToken != null) {
                            connection.setRequestProperty(applicationContext.getString(R.string.supertokensAntiCSRFHeaderKey), antiCSRFToken);
                        }

                        // Add package information to headers
                        connection.setRequestProperty(applicationContext.getString(R.string.supertokensNameHeaderKey), Utils.PACKAGE_PLATFORM);
                        connection.setRequestProperty(applicationContext.getString(R.string.supertokensVersionHeaderKey), BuildConfig.VERSION_NAME);

                        // Get the default cookie manager that is used, if null set a new one
                        CookieManager cookieManager = (CookieManager) CookieManager.getDefault();
                        if (cookieManager == null) {
                            // Passing null for cookie policy to use default
                            throw new IllegalAccessException("Please initialise a CookieManager.\n" +
                                    "For example: new CookieManager(new SuperTokensPersistentCookieStore(context), null).\n" +
                                    "SuperTokens provides a persistent cookie store called SuperTokensPersistentCookieStore.\n" +
                                    "For more information visit our documentation.");
                        }
                        // This will execute all the steps the user wants to do with the connection and returns the output that the user has configured
                        output = callback.runOnConnection(connection);
                        // Get the cookies from the response and store the idRefreshToken to storage
                        String idRefreshToken = connection.getHeaderField(applicationContext.getString(R.string.supertokensIdRefreshHeaderKey));
                        if (idRefreshToken != null) {
                            IdRefreshToken.setToken(applicationContext, idRefreshToken);
                        }

                        responseCode = connection.getResponseCode();
                    } finally {
                        refreshAPILock.readLock().unlock();
                    }

                    if (responseCode == SuperTokens.sessionExpiryStatusCode) {
                        // Network call threw UnauthorisedAccess, try to call the refresh token endpoint and retry original call
                        boolean retry = SuperTokensHttpURLConnection.handleUnauthorised(applicationContext, preRequestIdRefreshToken);
                        if (!retry) {
                            return output;
                        }
                    } else if (responseCode == -1) {
                        // If the response code is -1 then the response was not a valid HTTP response, return the output of the users execution
                        return output;
                    } else {
                        // Store the anti-CSRF token from the response headers
                        String responseAntiCSRFToken = connection.getHeaderField(applicationContext.getString(R.string.supertokensAntiCSRFHeaderKey));
                        if ( responseAntiCSRFToken != null ) {
                            AntiCSRF.setToken(applicationContext, IdRefreshToken.getToken(applicationContext), responseAntiCSRFToken);
                        }
                        return output;
                    }
                } finally {
                    if (connection != null) {
                        connection.disconnect();
                    }
                }
            }
        } finally {
            if ( IdRefreshToken.getToken(applicationContext) == null ) {
                AntiCSRF.removeToken(applicationContext);
            }
        }
    }

    private static boolean handleUnauthorised(Application applicationContext, String preRequestIdRefreshToken) throws IOException {
        if ( preRequestIdRefreshToken == null ) {
            String idRefreshToken = IdRefreshToken.getToken(applicationContext);
            return idRefreshToken != null;
        }

        Utils.Unauthorised unauthorisedResponse = onUnauthorisedResponse(SuperTokens.refreshTokenEndpoint,preRequestIdRefreshToken, applicationContext);

        if ( unauthorisedResponse.status == Utils.Unauthorised.UnauthorisedStatus.SESSION_EXPIRED ) {
            return false;
        } else if (unauthorisedResponse.status == Utils.Unauthorised.UnauthorisedStatus.API_ERROR) {
            throw unauthorisedResponse.error;
        }

        return true;
    }

    private static Utils.Unauthorised onUnauthorisedResponse(String refreshTokenEndpoint, String preRequestIdRefreshToken, Application applicationContext) {
        // this is intentionally not put in a loop because the loop in other projects is because locking has a timeout
        HttpURLConnection refreshTokenConnection = null;
        try {
            refreshAPILock.writeLock().lock();
            String postLockIdRefreshToken = IdRefreshToken.getToken(applicationContext);
            if ( postLockIdRefreshToken == null ) {
                return new Utils.Unauthorised(Utils.Unauthorised.UnauthorisedStatus.SESSION_EXPIRED);
            }

            if ( !postLockIdRefreshToken.equals(preRequestIdRefreshToken) ) {
                return new Utils.Unauthorised(Utils.Unauthorised.UnauthorisedStatus.RETRY);
            }

            URL refreshTokenUrl = new URL(refreshTokenEndpoint);
            refreshTokenConnection = (HttpURLConnection) refreshTokenUrl.openConnection();
            refreshTokenConnection.setRequestMethod("POST");

            // Add package information to headers
            refreshTokenConnection.setRequestProperty(applicationContext.getString(R.string.supertokensNameHeaderKey), Utils.PACKAGE_PLATFORM);
            refreshTokenConnection.setRequestProperty(applicationContext.getString(R.string.supertokensVersionHeaderKey), BuildConfig.VERSION_NAME);
            for (Map.Entry<String,String> entry: SuperTokens.refreshAPICustomHeaders.entrySet()) {
                refreshTokenConnection.setRequestProperty(entry.getKey(), entry.getValue());
            }

            CookieManager cookieManager = (CookieManager) CookieManager.getDefault();
            if (cookieManager == null) {
                throw new IllegalAccessException("Please initialise a CookieManager.\n" +
                        "For example: new CookieManager(new SuperTokensPersistentCookieStore(context), null).\n" +
                        "SuperTokens provides a persistent cookie store called SuperTokensPersistentCookieStore.\n" +
                        "For more information visit our documentation.");
            }
            refreshTokenConnection.connect();

            String idRefreshToken = refreshTokenConnection.getHeaderField(applicationContext.getString(R.string.supertokensIdRefreshHeaderKey));
            if (idRefreshToken != null) {
                IdRefreshToken.setToken(applicationContext, idRefreshToken);
            }

            if (refreshTokenConnection.getResponseCode() != 200) {
                throw new IOException(refreshTokenConnection.getResponseMessage());
            }

            String idRefreshAfterResponse = IdRefreshToken.getToken(applicationContext);
            if (idRefreshAfterResponse == null) {
                // removed by server
                return new Utils.Unauthorised(Utils.Unauthorised.UnauthorisedStatus.SESSION_EXPIRED);
            }

            String responseAntiCSRFToken = refreshTokenConnection.getHeaderField(applicationContext.getString(R.string.supertokensAntiCSRFHeaderKey));
            if (responseAntiCSRFToken != null) {
                AntiCSRF.setToken(applicationContext, IdRefreshToken.getToken(applicationContext), responseAntiCSRFToken);
            }

            return new Utils.Unauthorised(Utils.Unauthorised.UnauthorisedStatus.RETRY);
        } catch (Exception e) {
            IOException ioe = new IOException(e);
            if (e instanceof IOException) {
                ioe = (IOException) e;
            }
            String idRefreshToken = IdRefreshToken.getToken(applicationContext);
            if ( idRefreshToken == null ) {
                return new Utils.Unauthorised(Utils.Unauthorised.UnauthorisedStatus.SESSION_EXPIRED);
            }

            return new Utils.Unauthorised(Utils.Unauthorised.UnauthorisedStatus.API_ERROR, ioe);

        } finally {
            refreshAPILock.writeLock().unlock();
            if ( refreshTokenConnection != null ) {
                refreshTokenConnection.disconnect();
            }
        }
    }

    /**
     *
     * @return
     * @throws {@link IllegalAccessException} if SuperTokens.init is not called or application context is null
     * @throws {@link IOException} if request fails
     */
    public static boolean attemptRefreshingSession() throws IllegalAccessException, IOException {
        if ( !SuperTokens.isInitCalled ) {
            throw new IllegalAccessException("SuperTokens.init function needs to be called before using attemptRefreshingSession");
        }

        Application applicationContext = SuperTokens.contextWeakReference.get();
        if ( applicationContext == null ) {
            throw new IllegalAccessException("Application context is null");
        }

        try {
            String preRequestIdRefreshToken = IdRefreshToken.getToken(applicationContext);
            return handleUnauthorised(applicationContext, preRequestIdRefreshToken);
        } finally {
            String idRefreshToken = IdRefreshToken.getToken(applicationContext);
            if ( idRefreshToken == null ) {
                AntiCSRF.removeToken(applicationContext);
            }
        }
    }

    public interface SuperTokensHttpURLConnectionCallback<T> {
        T runOnConnection(HttpURLConnection con) throws IOException;
    }

}
