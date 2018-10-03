package com.mapbox.services.android.navigation.ui.v5.route;

import android.content.Context;
import android.content.res.TypedArray;
import android.graphics.Bitmap;
import android.graphics.drawable.Drawable;
import android.support.annotation.ColorInt;
import android.support.annotation.DrawableRes;
import android.support.annotation.NonNull;
import android.support.annotation.Nullable;
import android.support.annotation.StyleRes;
import android.support.v4.content.ContextCompat;
import android.support.v7.content.res.AppCompatResources;

import com.mapbox.api.directions.v5.models.DirectionsRoute;
import com.mapbox.api.directions.v5.models.RouteLeg;
import com.mapbox.geojson.Feature;
import com.mapbox.geojson.FeatureCollection;
import com.mapbox.geojson.LineString;
import com.mapbox.geojson.Point;
import com.mapbox.mapboxsdk.maps.MapboxMap;
import com.mapbox.mapboxsdk.style.expressions.Expression;
import com.mapbox.mapboxsdk.style.layers.Layer;
import com.mapbox.mapboxsdk.style.layers.LineLayer;
import com.mapbox.mapboxsdk.style.layers.Property;
import com.mapbox.mapboxsdk.style.layers.PropertyFactory;
import com.mapbox.mapboxsdk.style.layers.SymbolLayer;
import com.mapbox.services.android.navigation.ui.v5.R;
import com.mapbox.services.android.navigation.ui.v5.utils.MapImageUtils;
import com.mapbox.services.android.navigation.ui.v5.utils.MapUtils;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;

import static com.mapbox.mapboxsdk.style.expressions.Expression.color;
import static com.mapbox.mapboxsdk.style.expressions.Expression.exponential;
import static com.mapbox.mapboxsdk.style.expressions.Expression.get;
import static com.mapbox.mapboxsdk.style.expressions.Expression.interpolate;
import static com.mapbox.mapboxsdk.style.expressions.Expression.literal;
import static com.mapbox.mapboxsdk.style.expressions.Expression.match;
import static com.mapbox.mapboxsdk.style.expressions.Expression.stop;
import static com.mapbox.mapboxsdk.style.expressions.Expression.zoom;
import static com.mapbox.mapboxsdk.style.layers.Property.NONE;
import static com.mapbox.mapboxsdk.style.layers.Property.VISIBLE;
import static com.mapbox.mapboxsdk.style.layers.PropertyFactory.visibility;
import static com.mapbox.services.android.navigation.ui.v5.route.RouteConstants.WAYPOINT_LAYER_ID;

class MapRouteLine {

  @StyleRes
  private final int styleRes;
  @ColorInt
  private int routeDefaultColor;
  @ColorInt
  private int routeModerateColor;
  @ColorInt
  private int routeSevereColor;
  @ColorInt
  private int alternativeRouteDefaultColor;
  @ColorInt
  private int alternativeRouteModerateColor;
  @ColorInt
  private int alternativeRouteSevereColor;
  @ColorInt
  private int alternativeRouteShieldColor;
  @ColorInt
  private int routeShieldColor;
  @DrawableRes
  private int originWaypointIcon;
  @DrawableRes
  private int destinationWaypointIcon;

  private final HashMap<LineString, DirectionsRoute> routeLineStrings = new HashMap<>();
  private final List<FeatureCollection> featureCollections = new ArrayList<>();
  private final List<DirectionsRoute> directionsRoutes = new ArrayList<>();
  private final List<String> layerIds = new ArrayList<>();
  private final MapboxMap mapboxMap;
  private final Drawable originIcon;
  private final Drawable destinationIcon;

  private int primaryRouteIndex;
  private float routeScale;
  private float alternativeRouteScale;
  private String belowLayer;
  private boolean alternativesVisible = true;

  MapRouteLine(Context context, MapboxMap mapboxMap, int styleRes, String belowLayer) {
    this.mapboxMap = mapboxMap;
    this.styleRes = styleRes;
    this.belowLayer = belowLayer;
    obtainStyledAttributes(context);
    originIcon = AppCompatResources.getDrawable(context, originWaypointIcon);
    destinationIcon = AppCompatResources.getDrawable(context, destinationWaypointIcon);
    placeRouteBelow();
  }

  void draw(DirectionsRoute directionsRoute) {
    List<DirectionsRoute> route = new ArrayList<>();
    route.add(directionsRoute);
    draw(route);
  }

  void draw(List<DirectionsRoute> directionsRoutes) {
    clearRouteData();
    this.directionsRoutes.addAll(directionsRoutes);
    primaryRouteIndex = 0;
    alternativesVisible = directionsRoutes.size() > 1;
    generateFeatureCollectionList(directionsRoutes);
  }

  void redraw() {
    placeRouteBelow();
    drawRoutes();
    addDirectionWaypoints();
    toggleAlternativeVisibilityWith(alternativesVisible);
  }

  void toggleAlternativeVisibilityWith(boolean alternativesVisible) {
    this.alternativesVisible = alternativesVisible;
    toggleAlternativeVisibility(alternativesVisible);
  }

  void updateVisibilityTo(boolean isVisible) {
    // TODO hide / show based on boolean
    removeLayerIds();
    clearRouteListData();
  }

  List<DirectionsRoute> retrieveDirectionsRoutes() {
    return directionsRoutes;
  }

  void updatePrimaryRouteIndex(int primaryRouteIndex) {
    this.primaryRouteIndex = primaryRouteIndex;
  }

  int retrievePrimaryRouteIndex() {
    return primaryRouteIndex;
  }

  HashMap<LineString, DirectionsRoute> retrieveRouteLineStrings() {
    return routeLineStrings;
  }

  void updateRoutes() {
    // Update all route geometries to reflect their appropriate colors depending on if they are
    // alternative or primary.
    for (FeatureCollection featureCollection : featureCollections) {
      if (!(featureCollection.features().get(0).geometry() instanceof Point)) {
        int index = featureCollection.features().get(0).getNumberProperty(RouteConstants.INDEX_KEY).intValue();
        updatePrimaryShieldRoute(String.format(Locale.US, RouteConstants.ID_FORMAT,
          RouteConstants.GENERIC_ROUTE_SHIELD_LAYER_ID, index), index);
        updatePrimaryRoute(String.format(Locale.US, RouteConstants.ID_FORMAT,
          RouteConstants.GENERIC_ROUTE_LAYER_ID, index), index);
      }
    }
  }

  private void drawRoutes() {
    // Add all the sources, the list is traversed backwards to ensure the primary route always gets
    // drawn on top of the others since it initially has a index of zero.
    for (int i = featureCollections.size() - 1; i >= 0; i--) {
      MapUtils.updateMapSourceFromFeatureCollection(
        mapboxMap, featureCollections.get(i),
        featureCollections.get(i).features().get(0).getStringProperty(RouteConstants.SOURCE_KEY)
      );

      // Get some required information for the next step
      String sourceId = featureCollections.get(i).features()
        .get(0).getStringProperty(RouteConstants.SOURCE_KEY);
      int index = featureCollections.indexOf(featureCollections.get(i));

      // Add the layer IDs to a list so we can quickly remove them when needed without traversing
      // through all the map layers.
      layerIds.add(String.format(Locale.US, RouteConstants.ID_FORMAT,
        RouteConstants.GENERIC_ROUTE_SHIELD_LAYER_ID, index));
      layerIds.add(String.format(Locale.US, RouteConstants.ID_FORMAT,
        RouteConstants.GENERIC_ROUTE_LAYER_ID, index));

      // Add the route shield first followed by the route to ensure the shield is always on the
      // bottom.
      addRouteShieldLayer(layerIds.get(layerIds.size() - 2), sourceId, index);
      addRouteLayer(layerIds.get(layerIds.size() - 1), sourceId, index);
    }
  }

  private void clearRouteData() {
    removeLayerIds();
    clearRouteListData();
  }

  private void generateFeatureCollectionList(List<DirectionsRoute> routes) {
    new FeatureProcessingTask(routes, featureCollections, routeLineStrings, new OnFeaturesProcessedCallback() {
      @Override
      public void onFeaturesProcessed() {
        DirectionsRoute primaryRoute = directionsRoutes.get(primaryRouteIndex);
        FeatureCollection waypointFeatureCollection = buildWaypointFeatureCollectionFrom(primaryRoute);
        featureCollections.add(waypointFeatureCollection);
        drawRoutes();
        addDirectionWaypoints();
      }
    }).execute();
  }

  /**
   * The routes also display an icon for each waypoint in the route, we use symbol layers for this.
   */
  private FeatureCollection buildWaypointFeatureCollectionFrom(DirectionsRoute route) {
    final List<Feature> waypointFeatures = new ArrayList<>();
    for (RouteLeg leg : route.legs()) {
      waypointFeatures.add(getPointFromLineString(leg, 0));
      waypointFeatures.add(getPointFromLineString(leg, leg.steps().size() - 1));
    }
    return FeatureCollection.fromFeatures(waypointFeatures);
  }

  private void addDirectionWaypoints() {
    MapUtils.updateMapSourceFromFeatureCollection(
      mapboxMap, featureCollections.get(featureCollections.size() - 1), RouteConstants.WAYPOINT_SOURCE_ID
    );
    drawWaypointMarkers(mapboxMap, originIcon, destinationIcon);
  }

  private void drawWaypointMarkers(@NonNull MapboxMap mapboxMap, @Nullable Drawable originMarker,
                                   @Nullable Drawable destinationMarker) {
    if (originMarker == null || destinationMarker == null) {
      return;
    }
    SymbolLayer waypointLayer = mapboxMap.getLayerAs(WAYPOINT_LAYER_ID);
    if (waypointLayer == null) {
      Bitmap bitmap = MapImageUtils.getBitmapFromDrawable(originMarker);
      mapboxMap.addImage("originMarker", bitmap);
      bitmap = MapImageUtils.getBitmapFromDrawable(destinationMarker);
      mapboxMap.addImage("destinationMarker", bitmap);

      waypointLayer = new SymbolLayer(WAYPOINT_LAYER_ID, RouteConstants.WAYPOINT_SOURCE_ID)
        .withProperties(PropertyFactory.iconImage(match(
          Expression.toString(get("waypoint")), literal("originMarker"),
          stop("origin", literal("originMarker")),
          stop("destination", literal("destinationMarker"))
          )
          ),
          PropertyFactory.iconSize(interpolate(
            exponential(1.5f), zoom(),
            stop(0f, 0.6f),
            stop(10f, 0.8f),
            stop(12f, 1.3f),
            stop(22f, 2.8f)
          )),
          PropertyFactory.iconPitchAlignment(Property.ICON_PITCH_ALIGNMENT_MAP),
          PropertyFactory.iconAllowOverlap(true),
          PropertyFactory.iconIgnorePlacement(true)
        );
      layerIds.add(WAYPOINT_LAYER_ID);
      MapUtils.addLayerToMap(mapboxMap, waypointLayer, belowLayer);
    }
  }

  private void updatePrimaryRoute(String layerId, int index) {
    Layer layer = mapboxMap.getLayer(layerId);
    if (layer != null) {
      layer.setProperties(
        PropertyFactory.lineColor(match(
          Expression.toString(get(RouteConstants.CONGESTION_KEY)),
          color(index == primaryRouteIndex ? routeDefaultColor : alternativeRouteDefaultColor),
          stop("moderate", color(index == primaryRouteIndex ? routeModerateColor : alternativeRouteModerateColor)),
          stop("heavy", color(index == primaryRouteIndex ? routeSevereColor : alternativeRouteSevereColor)),
          stop("severe", color(index == primaryRouteIndex ? routeSevereColor : alternativeRouteSevereColor))
          )
        )
      );
      if (index == primaryRouteIndex) {
        mapboxMap.removeLayer(layer);
        mapboxMap.addLayerBelow(layer, WAYPOINT_LAYER_ID);
      }
    }
  }

  private void updatePrimaryShieldRoute(String layerId, int index) {
    Layer layer = mapboxMap.getLayer(layerId);
    if (layer != null) {
      layer.setProperties(
        PropertyFactory.lineColor(index == primaryRouteIndex ? routeShieldColor : alternativeRouteShieldColor)
      );
      if (index == primaryRouteIndex) {
        mapboxMap.removeLayer(layer);
        mapboxMap.addLayerBelow(layer, WAYPOINT_LAYER_ID);
      }
    }
  }

  /**
   * Add the route layer to the map either using the custom style values or the default.
   */
  private void addRouteLayer(String layerId, String sourceId, int index) {
    float scale = index == primaryRouteIndex ? routeScale : alternativeRouteScale;
    Layer routeLayer = new LineLayer(layerId, sourceId).withProperties(
      PropertyFactory.lineCap(Property.LINE_CAP_ROUND),
      PropertyFactory.lineJoin(Property.LINE_JOIN_ROUND),
      PropertyFactory.lineWidth(interpolate(
        exponential(1.5f), zoom(),
        stop(4f, 3f * scale),
        stop(10f, 4f * scale),
        stop(13f, 6f * scale),
        stop(16f, 10f * scale),
        stop(19f, 14f * scale),
        stop(22f, 18f * scale)
        )
      ),
      PropertyFactory.lineColor(match(
        Expression.toString(get(RouteConstants.CONGESTION_KEY)),
        color(index == primaryRouteIndex ? routeDefaultColor : alternativeRouteDefaultColor),
        stop("moderate", color(index == primaryRouteIndex ? routeModerateColor : alternativeRouteModerateColor)),
        stop("heavy", color(index == primaryRouteIndex ? routeSevereColor : alternativeRouteSevereColor)),
        stop("severe", color(index == primaryRouteIndex ? routeSevereColor : alternativeRouteSevereColor))
        )
      )
    );
    MapUtils.addLayerToMap(mapboxMap, routeLayer, belowLayer);
  }

  private void removeLayerIds() {
    if (!layerIds.isEmpty()) {
      for (String id : layerIds) {
        mapboxMap.removeLayer(id);
      }
    }
  }

  private void clearRouteListData() {
    if (!directionsRoutes.isEmpty()) {
      directionsRoutes.clear();
    }
    if (!routeLineStrings.isEmpty()) {
      routeLineStrings.clear();
    }
    if (!featureCollections.isEmpty()) {
      featureCollections.clear();
    }
  }

  /**
   * Add the route shield layer to the map either using the custom style values or the default.
   */
  private void addRouteShieldLayer(String layerId, String sourceId, int index) {
    float scale = index == primaryRouteIndex ? routeScale : alternativeRouteScale;
    Layer routeLayer = new LineLayer(layerId, sourceId).withProperties(
      PropertyFactory.lineCap(Property.LINE_CAP_ROUND),
      PropertyFactory.lineJoin(Property.LINE_JOIN_ROUND),
      PropertyFactory.lineWidth(interpolate(
        exponential(1.5f), zoom(),
        stop(10f, 7f),
        stop(14f, 10.5f * scale),
        stop(16.5f, 15.5f * scale),
        stop(19f, 24f * scale),
        stop(22f, 29f * scale)
        )
      ),
      PropertyFactory.lineColor(
        index == primaryRouteIndex ? routeShieldColor : alternativeRouteShieldColor)
    );
    MapUtils.addLayerToMap(mapboxMap, routeLayer, belowLayer);
  }

  /**
   * Loads in all the custom values the user might have set such as colors and line width scalars.
   * Anything they didn't set, results in using the default values.
   */
  private void obtainStyledAttributes(Context context) {
    TypedArray typedArray = context.obtainStyledAttributes(styleRes, R.styleable.NavigationMapRoute);

    // Primary Route attributes
    routeDefaultColor = typedArray.getColor(R.styleable.NavigationMapRoute_routeColor,
      ContextCompat.getColor(context, R.color.mapbox_navigation_route_layer_blue));
    routeModerateColor = typedArray.getColor(
      R.styleable.NavigationMapRoute_routeModerateCongestionColor,
      ContextCompat.getColor(context, R.color.mapbox_navigation_route_layer_congestion_yellow));
    routeSevereColor = typedArray.getColor(
      R.styleable.NavigationMapRoute_routeSevereCongestionColor,
      ContextCompat.getColor(context, R.color.mapbox_navigation_route_layer_congestion_red));
    routeShieldColor = typedArray.getColor(R.styleable.NavigationMapRoute_routeShieldColor,
      ContextCompat.getColor(context, R.color.mapbox_navigation_route_shield_layer_color));
    routeScale = typedArray.getFloat(R.styleable.NavigationMapRoute_routeScale, 1.0f);

    // Secondary Routes attributes
    alternativeRouteDefaultColor = typedArray.getColor(
      R.styleable.NavigationMapRoute_alternativeRouteColor,
      ContextCompat.getColor(context, R.color.mapbox_navigation_route_alternative_color));
    alternativeRouteModerateColor = typedArray.getColor(
      R.styleable.NavigationMapRoute_alternativeRouteModerateCongestionColor,
      ContextCompat.getColor(context, R.color.mapbox_navigation_route_alternative_congestion_yellow));
    alternativeRouteSevereColor = typedArray.getColor(
      R.styleable.NavigationMapRoute_alternativeRouteSevereCongestionColor,
      ContextCompat.getColor(context, R.color.mapbox_navigation_route_alternative_congestion_red));
    alternativeRouteShieldColor = typedArray.getColor(
      R.styleable.NavigationMapRoute_alternativeRouteShieldColor,
      ContextCompat.getColor(context, R.color.mapbox_navigation_route_alternative_shield_color));
    alternativeRouteScale = typedArray.getFloat(
      R.styleable.NavigationMapRoute_alternativeRouteScale, 1.0f);

    // Waypoint attributes
    originWaypointIcon = typedArray.getResourceId(
      R.styleable.NavigationMapRoute_originWaypointIcon, R.drawable.ic_route_origin);
    destinationWaypointIcon = typedArray.getResourceId(
      R.styleable.NavigationMapRoute_destinationWaypointIcon, R.drawable.ic_route_destination);

    typedArray.recycle();
  }

  private void placeRouteBelow() {
    if (belowLayer == null || belowLayer.isEmpty()) {
      List<Layer> styleLayers = mapboxMap.getLayers();
      for (int i = 0; i < styleLayers.size(); i++) {
        if (!(styleLayers.get(i) instanceof SymbolLayer)
          // Avoid placing the route on top of the user location layer
          && !styleLayers.get(i).getId().contains("mapbox-location")) {
          belowLayer = styleLayers.get(i).getId();
        }
      }
    }
  }

  private Feature getPointFromLineString(RouteLeg leg, int index) {
    Feature feature = Feature.fromGeometry(Point.fromLngLat(
      leg.steps().get(index).maneuver().location().longitude(),
      leg.steps().get(index).maneuver().location().latitude()
    ));
    feature.addStringProperty(RouteConstants.SOURCE_KEY, RouteConstants.WAYPOINT_SOURCE_ID);
    feature.addStringProperty("waypoint",
      index == 0 ? "origin" : "destination"
    );
    return feature;
  }

  private void toggleAlternativeVisibility(boolean visible) {
    for (String layerId : layerIds) {
      if (layerId.contains(String.valueOf(primaryRouteIndex))
        || layerId.contains(WAYPOINT_LAYER_ID)) {
        continue;
      }
      Layer layer = mapboxMap.getLayer(layerId);
      if (layer != null) {
        layer.setProperties(
          visibility(visible ? VISIBLE : NONE)
        );
      }
    }
  }
}
