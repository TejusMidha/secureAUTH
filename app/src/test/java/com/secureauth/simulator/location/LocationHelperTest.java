package com.secureauth.simulator.location;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/** The distance maths, checked against values computed independently with Python. */
public class LocationHelperTest {

    @Test
    public void theSamePointIsZeroKilometresAway() {
        assertEquals(0.0, LocationHelper.distanceKm(19.076, 72.8777, 19.076, 72.8777), 1e-9);
    }

    @Test
    public void knownCityDistances() {
        assertEquals(1153.243, LocationHelper.distanceKm(19.0760, 72.8777, 28.7041, 77.1025), 0.01);  // Mumbai - Delhi
        assertEquals(5570.230, LocationHelper.distanceKm(51.5074, -0.1278, 40.7128, -74.0060), 0.01); // London - New York
        assertEquals(120.152, LocationHelper.distanceKm(19.0760, 72.8777, 18.5204, 73.8567), 0.01);   // Mumbai - Pune
        assertEquals(19.016, LocationHelper.distanceKm(19.0760, 72.8777, 19.2183, 72.9781), 0.01);    // Mumbai - Thane
    }

    @Test
    public void oneDegreeOfLongitudeAtTheEquatorIsAbout111Km() {
        assertEquals(111.195, LocationHelper.distanceKm(0, 0, 0, 1), 0.01);
    }

    @Test
    public void oppositeSidesOfTheEarthAreHalfWayRoundIt() {
        assertEquals(20015.114, LocationHelper.distanceKm(0, 0, 0, 180), 0.01);
        assertEquals(20015.114, LocationHelper.distanceKm(90, 0, -90, 0), 0.01);
    }

    @Test
    public void distanceIsTheSameInBothDirections() {
        double there = LocationHelper.distanceKm(19.076, 72.8777, 51.5074, -0.1278);
        double back = LocationHelper.distanceKm(51.5074, -0.1278, 19.076, 72.8777);
        assertEquals(there, back, 1e-9);
    }

    @Test
    public void crossingTheDateLineTakesTheShortWay() {
        // 179.5 E and 179.5 W are only 1 degree of longitude apart, not 359
        assertEquals(111.195, LocationHelper.distanceKm(0, 179.5, 0, -179.5), 0.01);
    }

    @Test
    public void onlyMoreThanOneHundredKilometresIsSuspicious() {
        assertFalse(LocationHelper.isSuspicious(0));
        assertFalse(LocationHelper.isSuspicious(98.964));   // 0.89 degrees of latitude
        assertFalse(LocationHelper.isSuspicious(100.0));    // exactly 100 is not "more than"
        assertTrue(LocationHelper.isSuspicious(100.076));   // 0.9 degrees of latitude
        assertTrue(LocationHelper.isSuspicious(1153.243));
    }

    @Test
    public void theThresholdMatchesRealPairsOfPoints() {
        assertFalse(LocationHelper.isSuspicious(LocationHelper.distanceKm(0, 0, 0.89, 0)));
        assertTrue(LocationHelper.isSuspicious(LocationHelper.distanceKm(0, 0, 0.9, 0)));
        assertTrue(LocationHelper.isSuspicious(LocationHelper.distanceKm(19.0760, 72.8777, 18.5204, 73.8567)));
        assertFalse(LocationHelper.isSuspicious(LocationHelper.distanceKm(19.0760, 72.8777, 19.2183, 72.9781)));
    }

    @Test
    public void bothLocationPermissionsAreRequestedTogether() {
        assertEquals(2, LocationHelper.PERMISSIONS.length);
        assertEquals("android.permission.ACCESS_FINE_LOCATION", LocationHelper.PERMISSIONS[0]);
        assertEquals("android.permission.ACCESS_COARSE_LOCATION", LocationHelper.PERMISSIONS[1]);
    }
}
