package com.mapbox.services.android.navigation.ui.v5.route;

import android.os.AsyncTask;

import com.mapbox.api.directions.v5.models.DirectionsRoute;
import com.mapbox.api.directions.v5.models.RouteLeg;
import com.mapbox.core.constants.Constants;
import com.mapbox.geojson.Feature;
import com.mapbox.geojson.FeatureCollection;
import com.mapbox.geojson.LineString;
import com.mapbox.geojson.Point;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;

class FeatureProcessingTask extends AsyncTask<Void, Void, Void> {

  private final List<DirectionsRoute> routes;
  private final List<FeatureCollection> featureCollections;
  private final HashMap<LineString, DirectionsRoute> routeLineStrings;
  private final OnFeaturesProcessedCallback callback;

  FeatureProcessingTask(List<DirectionsRoute> routes,
                        List<FeatureCollection> featureCollections,
                        HashMap<LineString, DirectionsRoute> routeLineStrings,
                        OnFeaturesProcessedCallback callback) {
    this.routes = routes;
    this.featureCollections = featureCollections;
    this.routeLineStrings = routeLineStrings;
    this.callback = callback;
  }

  @Override
  protected Void doInBackground(Void... voids) {
    for (int i = 0; i < routes.size(); i++) {
      DirectionsRoute route = routes.get(i);
      FeatureCollection trafficCollection = createTrafficCollection(route, i);
      featureCollections.add(trafficCollection);
    }
    return null;
  }

  @Override
  protected void onPostExecute(Void result) {
    super.onPostExecute(result);
    callback.onFeaturesProcessed();
  }

  private FeatureCollection createTrafficCollection(DirectionsRoute route, int routeIndex) {
    final List<Feature> features = new ArrayList<>();
    LineString originalGeometry = LineString.fromPolyline(route.geometry(), Constants.PRECISION_6);
    buildRouteFeatureFromGeometry(routeIndex, features, originalGeometry);
    routeLineStrings.put(originalGeometry, route);
    LineString lineString = LineString.fromPolyline(route.geometry(), Constants.PRECISION_6);
    buildTrafficFeaturesFromRoute(route, routeIndex, features, lineString);
    return FeatureCollection.fromFeatures(features);
  }

  private void buildRouteFeatureFromGeometry(int index, List<Feature> features, LineString originalGeometry) {
    Feature feat = Feature.fromGeometry(originalGeometry);
    String source = String.format(Locale.US, RouteConstants.ID_FORMAT, RouteConstants.GENERIC_ROUTE_SOURCE_ID, index);
    feat.addStringProperty(RouteConstants.SOURCE_KEY, source);
    feat.addNumberProperty(RouteConstants.INDEX_KEY, index);
    features.add(feat);
  }

  private void buildTrafficFeaturesFromRoute(DirectionsRoute route, int index,
                                             List<Feature> features, LineString lineString) {
    for (RouteLeg leg : route.legs()) {
      if (leg.annotation() != null && leg.annotation().congestion() != null) {
        for (int i = 0; i < leg.annotation().congestion().size(); i++) {
          // See https://github.com/mapbox/mapbox-navigation-android/issues/353
          if (leg.annotation().congestion().size() + 1 <= lineString.coordinates().size()) {

            List<Point> points = new ArrayList<>();
            points.add(lineString.coordinates().get(i));
            points.add(lineString.coordinates().get(i + 1));

            LineString congestionLineString = LineString.fromLngLats(points);
            Feature feature = Feature.fromGeometry(congestionLineString);
            feature.addStringProperty(RouteConstants.CONGESTION_KEY, leg.annotation().congestion().get(i));
            feature.addStringProperty(RouteConstants.SOURCE_KEY, String.format(Locale.US, RouteConstants.ID_FORMAT,
              RouteConstants.GENERIC_ROUTE_SOURCE_ID, index));
            feature.addNumberProperty(RouteConstants.INDEX_KEY, index);
            features.add(feature);
          }
        }
      } else {
        Feature feature = Feature.fromGeometry(lineString);
        features.add(feature);
      }
    }
  }
}
