package testapp;

import com.mapbox.api.directions.v5.models.DirectionsRoute;
import com.mapbox.services.android.navigation.testapp.test.TestNavigationViewActivity;
import com.mapbox.services.android.navigation.ui.v5.NavigationViewOptions;

import org.junit.Rule;
import org.junit.Test;

import java.io.IOException;
import java.io.InputStream;

import testapp.activity.BaseNavigationViewActivityTest;
import testapp.rules.BatteryStatsDumpsysRule;
import testapp.rules.CpuInfoDumpsysRule;
import testapp.rules.GraphicsDumpsysRule;
import testapp.rules.MemoryInfoDumpsysRule;
import testapp.rules.TraceRule;

import static testapp.action.NavigationViewAction.invoke;

public class NavigationViewPerformanceTest extends BaseNavigationViewActivityTest {

  @Rule
  public TraceRule traceRule = new TraceRule();

  @Rule
  public BatteryStatsDumpsysRule batteryRule = new BatteryStatsDumpsysRule();

  @Rule
  public GraphicsDumpsysRule graphicsRule = new GraphicsDumpsysRule();

  @Rule
  public CpuInfoDumpsysRule cpuInfoRule = new CpuInfoDumpsysRule();

  @Rule
  public MemoryInfoDumpsysRule memoryInfoRule = new MemoryInfoDumpsysRule();

  @Override
  protected Class getActivityClass() {
    return TestNavigationViewActivity.class;
  }

  @Test
  public void testNavigationViewPerformance_DCHQ_to_DCA() {
    validateTestSetup();

    DirectionsRoute testRoute = DirectionsRoute.fromJson(loadJsonFromAsset("dchq_to_dca_route.json"));
    NavigationViewOptions navigationViewOptions = NavigationViewOptions.builder()
      .directionsRoute(testRoute)
      .shouldSimulateRoute(true)
      .build();

    invoke(getNavigationView(), (uiController, navigationView) -> {
      navigationView.startNavigation(navigationViewOptions);
      uiController.loopMainThreadForAtLeast(5000);
    });
  }

  private String loadJsonFromAsset(String filename) {
    try {
      InputStream is = getAssetManager().open(filename);
      int size = is.available();
      byte[] buffer = new byte[size];
      is.read(buffer);
      is.close();
      return new String(buffer, "UTF-8");

    } catch (IOException ex) {
      ex.printStackTrace();
      return null;
    }
  }
}
