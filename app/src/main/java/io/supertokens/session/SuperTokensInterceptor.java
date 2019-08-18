package io.supertokens.session;

import android.app.Application;
import io.supertokens.session.utils.AntiCSRF;
import io.supertokens.session.utils.IdRefreshToken;
import okhttp3.FormBody;
import okhttp3.Interceptor;
import okhttp3.Request;
import okhttp3.Response;
import org.jetbrains.annotations.NotNull;

import java.io.IOException;
import java.util.List;

@SuppressWarnings("unused")
public class SuperTokensInterceptor implements Interceptor {
    private static final Object refreshTokenLock = new Object();

    @NotNull
    @Override
    public Response intercept(@NotNull Chain chain) throws IOException {
        if ( !SuperTokens.isInitCalled ) {
            throw new IOException("SuperTokens.init function needs to be called before using interceptors");
        }

        Application applicationContext = SuperTokens.contextWeakReference.get();
        if ( applicationContext == null ) {
            throw new IOException("Application context is null");
        }

        try {
            while (true) {
                Request.Builder requestBuilder = chain.request().newBuilder();

                String preRequestIdRefreshToken = IdRefreshToken.getToken(applicationContext);
                String antiCSRFToken = AntiCSRF.getToken(applicationContext, preRequestIdRefreshToken);

                if ( antiCSRFToken != null ) {
                    requestBuilder.header(applicationContext.getString(R.string.supertokensAntiCSRFHeaderKey), antiCSRFToken);
                }

                Request request = requestBuilder.build();
                Response response =  chain.proceed(request);

                if ( response.code() == SuperTokens.sessionExpiryStatusCode ) {
                    response.close();
                    Boolean retry = SuperTokensInterceptor.handleUnauthorised(applicationContext, preRequestIdRefreshToken, chain);
                    if ( !retry ) {
                        return response;
                    }
                } else {
                    SuperTokensInterceptor.saveAntiCSRFFromResponse(applicationContext, response);
                    SuperTokensInterceptor.saveIdRefreshFromSetCookie(applicationContext, response);

                    return response;
                }
            }
        } finally {
            if ( IdRefreshToken.getToken(applicationContext) == null ) {
                AntiCSRF.removeToken(applicationContext);
            }
        }
    }

    private static Boolean handleUnauthorised(Application applicationContext, String preRequestIdRefreshToken, Chain chain) throws IOException {
        if ( preRequestIdRefreshToken == null ) {
            String idRefresh = IdRefreshToken.getToken(applicationContext);
            return idRefresh != null;
        }

        synchronized (refreshTokenLock) {
            String postLockIdRefreshToken = IdRefreshToken.getToken(applicationContext);

            if ( postLockIdRefreshToken == null ) {
                return false;
            }

            if ( !postLockIdRefreshToken.equals(preRequestIdRefreshToken )) {
                return true;
            }

            Request.Builder refreshRequestBuilder = new Request.Builder();
            refreshRequestBuilder.url(SuperTokens.refreshTokenEndpoint);
            refreshRequestBuilder.method("POST", new FormBody.Builder().build());

            Request refreshRequest = refreshRequestBuilder.build();
            Response refreshResponse = chain.proceed(refreshRequest);

            if ( refreshResponse.code() != 200 ) {
                refreshResponse.close();
                throw new IOException(refreshResponse.message());
            }

            SuperTokensInterceptor.saveIdRefreshFromSetCookie(applicationContext, refreshResponse);
            SuperTokensInterceptor.saveAntiCSRFFromResponse(applicationContext, refreshResponse);

            if ( IdRefreshToken.getToken(applicationContext) == null ) {
                refreshResponse.close();
                return false;
            }
        }

        String idRefreshToken = IdRefreshToken.getToken(applicationContext);
        if ( idRefreshToken == null ) {
            return false;
        } else if (!idRefreshToken.equals(preRequestIdRefreshToken)) {
            return true;
        }

        return true;
    }

    private static void saveAntiCSRFFromResponse(Application applicationContext, Response response) {
        String antiCSRF = response.header(applicationContext.getString(R.string.supertokensAntiCSRFHeaderKey));
        if ( antiCSRF != null ) {
            AntiCSRF.setToken(applicationContext, IdRefreshToken.getToken(applicationContext), antiCSRF);
        }
    }

    private static void saveIdRefreshFromSetCookie(Application applicationContext, Response response) {
        List<String> setCookie = response.headers(applicationContext.getString(R.string.supertokensSetCookieHeaderKey));
        IdRefreshToken.saveIdRefreshFromSetCookieOkhttp(applicationContext, setCookie);
    }
}
