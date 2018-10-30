package com.mapbox.services.android.navigation.v5.navigation;

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.NetworkInfo;

import com.mapbox.api.speech.v1.MapboxSpeech;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.concurrent.TimeUnit;

import okhttp3.Cache;
import okhttp3.CacheControl;
import okhttp3.Interceptor;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;
import retrofit2.Call;
import retrofit2.Callback;
import timber.log.Timber;

public class VoiceInstructionLoader {
  private static final String OKHTTP_INSTRUCTION_CACHE = "okhttp-instruction-cache";
  private static final long TEN_MEGABYTE_CACHE_SIZE = 10 * 1024 * 1024;
  private static final int VOICE_INSTRUCTIONS_TO_EVICT_THRESHOLD = 4;
  private static final String SSML_TEXT_TYPE = "ssml";
  private ConnectivityManager connectivityManager;
  private String accessToken;
  private Cache cache;
  private MapboxSpeech.Builder mapboxSpeechBuilder = null;
  private List<String> urlsCached;

  public VoiceInstructionLoader(Context context, String accessToken) {
    this.connectivityManager = (ConnectivityManager) context.getSystemService(Context.CONNECTIVITY_SERVICE);
    this.accessToken = accessToken;
    this.urlsCached = new ArrayList<>();
    setup(context);
  }

  public void setupMapboxSpeechBuilder(String language) {
    if (mapboxSpeechBuilder == null) {
      mapboxSpeechBuilder = MapboxSpeech.builder()
        .accessToken(accessToken)
        .language(language)
        .cache(cache)
        .interceptor(provideOfflineCacheInterceptor());
    }
  }

  public void requestInstruction(String instruction, String textType, Callback<ResponseBody> callback) {
    MapboxSpeech mapboxSpeech = mapboxSpeechBuilder
      .instruction(instruction)
      .textType(textType)
      .build();
    mapboxSpeech.enableDebug(true);
    mapboxSpeech.enqueueCall(callback);
  }

  public void evictVoiceInstructions() {
    List<String> urlsToRemove = new ArrayList<>();
    for (int i = 0; i < urlsCached.size() && i < VOICE_INSTRUCTIONS_TO_EVICT_THRESHOLD; i++) {
      String urlToRemove = urlsCached.get(i);
      try {
        Iterator<String> urlsCurrentlyCached = cache.urls();
        for (Iterator<String> urlCached = urlsCurrentlyCached; urlCached.hasNext(); ) {
          String url = urlCached.next();
          if (url.equals(urlToRemove)) {
            urlCached.remove();
            urlsToRemove.add(urlToRemove);
            Timber.d("DEBUG url to evict " + urlToRemove);
          }
        }
      } catch (IOException exception) {
        exception.printStackTrace();
      }
    }
    urlsCached.removeAll(urlsToRemove);
  }

  public void flushCache() {
    try {
      cache.delete();
    } catch (IOException exception) {
      Timber.e(exception);
    }
  }

  public void cacheInstructions(List<String> instructions) {
    for (String instruction : instructions) {
      cacheInstruction(instruction);
    }
  }

  private void cacheInstruction(String instruction) {
    requestInstruction(instruction, SSML_TEXT_TYPE, new Callback<ResponseBody>() {
      @Override
      public void onResponse(Call<ResponseBody> call, retrofit2.Response<ResponseBody> response) {
        response.body().byteStream();
        response.body().close();
        urlsCached.add(call.request().url().toString());
        Timber.d("DEBUG url to cache " + call.request().url().toString());
      }

      @Override
      public void onFailure(Call<ResponseBody> call, Throwable throwable) {
        Timber.e("onFailure cache instruction");
      }
    });
  }

  private void setup(Context context) {
    cache = new Cache(new File(context.getCacheDir(), OKHTTP_INSTRUCTION_CACHE), TEN_MEGABYTE_CACHE_SIZE);
  }

  private Interceptor provideOfflineCacheInterceptor() {
    return new Interceptor() {
      @Override
      public Response intercept(Chain chain) throws IOException {
        Request request = chain.request();
        if (!hasNetworkConnection()) {
          CacheControl cacheControl = new CacheControl.Builder()
            .maxStale(3, TimeUnit.DAYS)
            .build();
          request = request.newBuilder()
            .cacheControl(cacheControl)
            .build();
        }
        return chain.proceed(request);
      }
    };
  }

  @SuppressWarnings( {"MissingPermission"})
  private boolean hasNetworkConnection() {
    if (connectivityManager == null) {
      return false;
    }
    NetworkInfo activeNetwork = connectivityManager.getActiveNetworkInfo();
    return activeNetwork != null && activeNetwork.isConnected();
  }
}
