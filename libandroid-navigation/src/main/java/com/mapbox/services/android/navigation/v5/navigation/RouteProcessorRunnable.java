package com.mapbox.services.android.navigation.v5.navigation;

import android.location.Location;
import android.os.Handler;

import com.mapbox.api.directions.v5.models.DirectionsRoute;
import com.mapbox.api.directions.v5.models.LegStep;
import com.mapbox.api.directions.v5.models.RouteLeg;
import com.mapbox.api.directions.v5.models.VoiceInstructions;
import com.mapbox.navigator.NavigationStatus;
import com.mapbox.services.android.navigation.v5.milestone.Milestone;
import com.mapbox.services.android.navigation.v5.milestone.VoiceInstructionMilestone;
import com.mapbox.services.android.navigation.v5.offroute.OffRoute;
import com.mapbox.services.android.navigation.v5.offroute.OffRouteDetector;
import com.mapbox.services.android.navigation.v5.route.FasterRoute;
import com.mapbox.services.android.navigation.v5.routeprogress.RouteProgress;
import com.mapbox.services.android.navigation.v5.snap.Snap;
import com.mapbox.services.android.navigation.v5.snap.SnapToRoute;

import java.util.ArrayList;
import java.util.Date;
import java.util.List;

import timber.log.Timber;

class RouteProcessorRunnable implements Runnable {

  private static final int ONE_SECOND_IN_MILLISECONDS = 1000;
  private static final int MAX_VOICE_INSTRUCTIONS_TO_CACHE = 10;
  private static final int VOICE_INSTRUCTIONS_TO_CACHE_THRESHOLD = 5;
  private final NavigationRouteProcessor routeProcessor;
  private final MapboxNavigation navigation;
  private final Handler workerHandler;
  private final Handler responseHandler;
  private final RouteProcessorBackgroundThread.Listener listener;
  private Location rawLocation;
  private VoiceInstructionLoader voiceInstructionLoader;
  private int voiceInstructionsToAnnounce = 0;
  private int totalVoiceInstructions = 0;
  private int currentVoiceInstructionsCachedIndex = 0;
  private boolean isFirstRoute = false;
  private boolean isVoiceInstructionsToCacheThresholdReached = false;

  RouteProcessorRunnable(NavigationRouteProcessor routeProcessor,
                         MapboxNavigation navigation,
                         Handler workerHandler,
                         Handler responseHandler,
                         RouteProcessorBackgroundThread.Listener listener) {
    this.routeProcessor = routeProcessor;
    this.navigation = navigation;
    this.workerHandler = workerHandler;
    this.responseHandler = responseHandler;
    this.listener = listener;
    this.voiceInstructionLoader = navigation.retrieveInstructionLoader();
  }

  @Override
  public void run() {
    process();
  }

  void updateRawLocation(Location rawLocation) {
    this.rawLocation = rawLocation;
  }

  private void process() {
    MapboxNavigator mapboxNavigator = navigation.retrieveMapboxNavigator();
    MapboxNavigationOptions options = navigation.options();
    DirectionsRoute route = navigation.getRoute();

    NavigationStatus status = mapboxNavigator.retrieveStatus(new Date(),
      options.navigationLocationEngineIntervalLagInMilliseconds());
    RouteProgress routeProgress = routeProcessor.buildNewRouteProgress(status, route);

    NavigationEngineFactory engineFactory = navigation.retrieveEngineFactory();
    final boolean userOffRoute = isUserOffRoute(options, status, rawLocation, routeProgress, engineFactory);
    final Location snappedLocation = findSnappedLocation(status, rawLocation, routeProgress, engineFactory);
    final boolean checkFasterRoute = checkFasterRoute(options, snappedLocation, routeProgress, engineFactory,
      userOffRoute);
    final List<Milestone> milestones = findTriggeredMilestones(navigation, routeProgress);

    sendUpdateToResponseHandler(userOffRoute, milestones, snappedLocation, checkFasterRoute, routeProgress);
    routeProcessor.updatePreviousRouteProgress(routeProgress);
    workerHandler.postDelayed(this, ONE_SECOND_IN_MILLISECONDS);
  }

  private boolean isUserOffRoute(MapboxNavigationOptions options, NavigationStatus status, Location rawLocation,
                                 RouteProgress routeProgress, NavigationEngineFactory engineFactory) {
    OffRoute offRoute = engineFactory.retrieveOffRouteEngine();
    if (offRoute instanceof OffRouteDetector) {
      return ((OffRouteDetector) offRoute).isUserOffRouteWith(status);
    }
    return offRoute.isUserOffRoute(rawLocation, routeProgress, options);
  }

  private Location findSnappedLocation(NavigationStatus status, Location rawLocation, RouteProgress routeProgress,
                                       NavigationEngineFactory engineFactory) {
    Snap snap = engineFactory.retrieveSnapEngine();
    if (snap instanceof SnapToRoute) {
      return ((SnapToRoute) snap).getSnappedLocationWith(status);
    }
    return snap.getSnappedLocation(rawLocation, routeProgress);
  }

  private boolean checkFasterRoute(MapboxNavigationOptions options, Location rawLocation, RouteProgress routeProgress,
                                   NavigationEngineFactory engineFactory, boolean userOffRoute) {
    FasterRoute fasterRoute = engineFactory.retrieveFasterRouteEngine();
    boolean fasterRouteDetectionEnabled = options.enableFasterRouteDetection();
    return fasterRouteDetectionEnabled
      && !userOffRoute
      && fasterRoute.shouldCheckFasterRoute(rawLocation, routeProgress);
  }

  private List<Milestone> findTriggeredMilestones(MapboxNavigation mapboxNavigation, RouteProgress routeProgress) {
    RouteProgress previousRouteProgress = routeProcessor.retrievePreviousRouteProgress();
    if (previousRouteProgress == null) {
      previousRouteProgress = routeProgress;
    }
    cacheInstructions(previousRouteProgress, routeProgress, mapboxNavigation.retrieveMapboxNavigator());
    List<Milestone> milestones = new ArrayList<>();
    for (Milestone milestone : mapboxNavigation.getMilestones()) {
      if (milestone.isOccurring(previousRouteProgress, routeProgress)) {
        milestones.add(milestone);
        if (milestone instanceof VoiceInstructionMilestone) {
          voiceInstructionsToAnnounce++;
          Timber.d("DEBUG voiceInstructionsToAnnounce " + voiceInstructionsToAnnounce);
          if (voiceInstructionsToAnnounce % VOICE_INSTRUCTIONS_TO_CACHE_THRESHOLD == 0) {
            Timber.d("DEBUG voice instructions to announce threshold reached!");
            isVoiceInstructionsToCacheThresholdReached = true;
          }
        }
      }
    }
    return milestones;
  }

  private void cacheInstructions(RouteProgress previousRouteProgress, RouteProgress routeProgress,
                                 MapboxNavigator mapboxNavigator) {
    DirectionsRoute previousRoute = previousRouteProgress.directionsRoute();
    DirectionsRoute currentRoute = routeProgress.directionsRoute();
    if ((!isFirstRoute && previousRouteProgress.equals(routeProgress)) || !previousRoute.equals(currentRoute)) {
      isFirstRoute = true;
      voiceInstructionsToAnnounce = 0;
      totalVoiceInstructions = 0;
      currentVoiceInstructionsCachedIndex = 0;
      isVoiceInstructionsToCacheThresholdReached = false;
      for (int i = 0; i < currentRoute.legs().size(); i++) {
        RouteLeg leg = currentRoute.legs().get(i);
        for (int j = 0; j < leg.steps().size(); j++) {
          LegStep step = leg.steps().get(j);
          for (VoiceInstructions ignored : step.voiceInstructions()) {
            totalVoiceInstructions++;
          }
        }
      }
      Timber.d("DEBUG totalVoiceInstructions " + totalVoiceInstructions);
      List<String> voiceInstructionsToCache = new ArrayList<>();
      for (int i = currentVoiceInstructionsCachedIndex; i < totalVoiceInstructions; i++) {
        voiceInstructionsToCache.add(mapboxNavigator.retrieveVoiceInstruction(i).getSsmlAnnouncement());
        currentVoiceInstructionsCachedIndex++;
        Timber.d("DEBUG currentVoiceInstructionsCachedIndex++ " + currentVoiceInstructionsCachedIndex);
        if ((currentVoiceInstructionsCachedIndex + 1) % MAX_VOICE_INSTRUCTIONS_TO_CACHE == 0) {
          Timber.d("DEBUG current voice instructions cached index threshold reached!");
          break;
        }
      }
      voiceInstructionLoader.cacheInstructions(voiceInstructionsToCache);
    }
    if (isVoiceInstructionsToCacheThresholdReached) {
      Timber.d("DEBUG isVoiceInstructionsToCacheThresholdReached");
      isVoiceInstructionsToCacheThresholdReached = false;
      voiceInstructionLoader.evictVoiceInstructions();
      List<String> voiceInstructionsToCache = new ArrayList<>();
      for (int i = currentVoiceInstructionsCachedIndex; i < totalVoiceInstructions; i++) {
        voiceInstructionsToCache.add(mapboxNavigator.retrieveVoiceInstruction(i).getSsmlAnnouncement());
        currentVoiceInstructionsCachedIndex++;
        Timber.d("DEBUG isVoiceInstructionsToCacheThresholdReached currentVoiceInstructionsCachedIndex++ "
          + currentVoiceInstructionsCachedIndex);
        if ((currentVoiceInstructionsCachedIndex + 1) % MAX_VOICE_INSTRUCTIONS_TO_CACHE == 0) {
          Timber.d("DEBUG isVoiceInstructionsToCacheThresholdReached current voice instructions cached index "
            + "threshold reached!");
          break;
        }
      }
      voiceInstructionLoader.cacheInstructions(voiceInstructionsToCache);
    }
  }

  private void sendUpdateToResponseHandler(final boolean userOffRoute, final List<Milestone> milestones,
                                           final Location location, final boolean checkFasterRoute,
                                           final RouteProgress finalRouteProgress) {
    responseHandler.post(new Runnable() {
      @Override
      public void run() {
        listener.onNewRouteProgress(location, finalRouteProgress);
        listener.onMilestoneTrigger(milestones, finalRouteProgress);
        listener.onUserOffRoute(location, userOffRoute);
        listener.onCheckFasterRoute(location, finalRouteProgress, checkFasterRoute);
      }
    });
  }
}
