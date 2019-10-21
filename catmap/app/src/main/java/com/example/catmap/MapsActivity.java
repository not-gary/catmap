package com.example.catmap;

import androidx.fragment.app.FragmentActivity;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.drawable.Drawable;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.nfc.Tag;
import android.os.Bundle;
import android.util.Log;

import com.google.android.gms.maps.CameraUpdateFactory;
import com.google.android.gms.maps.GoogleMap;
import com.google.android.gms.maps.LocationSource;
import com.google.android.gms.maps.MapFragment;
import com.google.android.gms.maps.OnMapReadyCallback;
import com.google.android.gms.maps.SupportMapFragment;
import com.google.android.gms.maps.model.BitmapDescriptor;
import com.google.android.gms.maps.model.GroundOverlay;
import com.google.android.gms.maps.model.GroundOverlayOptions;
import com.google.android.gms.maps.model.LatLng;
import com.google.android.gms.maps.model.MarkerOptions;
import com.google.android.gms.maps.model.BitmapDescriptorFactory;
import com.google.android.gms.maps.model.Marker;
import com.google.android.gms.maps.model.MarkerOptions;
import com.google.android.gms.maps.model.Polyline;

import com.google.android.gms.maps.model.PolylineOptions;
import com.indooratlas.android.sdk.IALocation;
import com.indooratlas.android.sdk.IALocationListener;
import com.indooratlas.android.sdk.IALocationManager;
import com.indooratlas.android.sdk.IALocationRequest;
import com.indooratlas.android.sdk.IARegion;
import com.indooratlas.android.sdk.IARoute;
import com.indooratlas.android.sdk.IAWayfindingListener;
import com.indooratlas.android.sdk.IAWayfindingRequest;
import com.indooratlas.android.sdk.resources.IAFloorPlan;
import com.indooratlas.android.sdk.resources.IALatLng;
import com.indooratlas.android.sdk.resources.IALocationListenerSupport;

import com.squareup.picasso.Picasso;
import com.squareup.picasso.RequestCreator;
import com.squareup.picasso.Target;

import java.util.ArrayList;
import java.util.List;

public class MapsActivity extends FragmentActivity implements LocationListener, OnMapReadyCallback {
    private static final int MAX_DIMENSION = 2048;

    private GoogleMap mMap;
    private List<Polyline> mPolylines = new ArrayList<>();
    private Marker mDestinationMarker;

    private Target mTarget;

    private boolean mCameraUpdate = true;
    private boolean mIndoorLock = false;

    private IALocationManager mIALocationManager;
    private IARegion mVenue = null;
    private IARegion mFloorPlan = null;
    private int mFloorLevel;
    private GroundOverlay mGroundOverlay = null;
    private IARoute mRoute;

    private IALocationListener mListener = new IALocationListenerSupport() {
        @Override
        public void onLocationChanged(IALocation location) {
            if (mMap == null) return;

            final LatLng latLng = new LatLng(location.getLatitude(), location.getLongitude());

            final int newFloorLevel = location.getFloorLevel();
            if (mFloorLevel != newFloorLevel)
                updateRoute();
            mFloorLevel = newFloorLevel;

            if (mCameraUpdate) {
                mMap.animateCamera(CameraUpdateFactory.newLatLngZoom(latLng, 17.5f));
                mCameraUpdate = false;
            }
        }
    };

    private IARegion.Listener mRegionListener = new IARegion.Listener() {
        @Override
        public void onEnterRegion(IARegion region) {
            if (region.getType() == IARegion.TYPE_VENUE) {
                mVenue = region;

                mIALocationManager.lockIndoors(false);
                mIndoorLock = false;
            } else if (region.getType() == IARegion.TYPE_FLOOR_PLAN) {
                if (mGroundOverlay == null || !region.equals(mFloorPlan)) {
                    mCameraUpdate = true;

                    if (mGroundOverlay != null) {
                        mGroundOverlay.remove();
                        mGroundOverlay = null;
                    }

                    mFloorPlan = region;
                    final IAFloorPlan floorPlan = region.getFloorPlan();
                    mTarget = new Target() {
                        @Override
                        public void onBitmapLoaded(Bitmap bitmap, Picasso.LoadedFrom from) {
                            if (mFloorPlan != null && floorPlan.getId().equals(mFloorPlan.getId())) {
                                if (mGroundOverlay != null)
                                    mGroundOverlay.remove();

                                if (mMap != null) {
                                    BitmapDescriptor bitmapDescriptor = BitmapDescriptorFactory.fromBitmap(bitmap);
                                    IALatLng latLng = floorPlan.getCenter();
                                    LatLng center = new LatLng(latLng.latitude, latLng.longitude);
                                    GroundOverlayOptions floorPlanOverlay = new GroundOverlayOptions()
                                            .image(bitmapDescriptor)
                                            .zIndex(0.0f)
                                            .position(center, floorPlan.getWidthMeters(), floorPlan.getHeightMeters())
                                            .bearing(floorPlan.getBearing());

                                    mGroundOverlay = mMap.addGroundOverlay(floorPlanOverlay);
                                }
                            }
                        }

                        @Override
                        public void onBitmapFailed(Drawable errorDrawable) {
                            mFloorPlan = null;
                        }

                        @Override
                        public void onPrepareLoad(Drawable placeHolderDrawable) {
                            // Empty.
                        }
                    };

                    RequestCreator request = Picasso.with(MapsActivity.this).load(floorPlan.getUrl());
                    int bmWidth = floorPlan.getBitmapWidth();
                    int bmHeight = floorPlan.getBitmapHeight();

                    if (bmWidth > MAX_DIMENSION) request.resize(MAX_DIMENSION, 0);
                    else if (bmHeight > MAX_DIMENSION) request.resize(0, MAX_DIMENSION);

                    request.into(mTarget);
                } else {
                    mGroundOverlay.setTransparency(0.0f);
                }

                mIALocationManager.lockIndoors(true);
                mIndoorLock = true;
            }
        }

        @Override
        public void onExitRegion(IARegion region) {
            if (region.getType() == IARegion.TYPE_VENUE) {
                mVenue = region;

                mIALocationManager.lockIndoors(false);
                mIndoorLock = false;
            } else if (region.getType() == IARegion.TYPE_FLOOR_PLAN) {
                if (mGroundOverlay != null)
                    mGroundOverlay.setTransparency(0.5f);

                mIALocationManager.lockIndoors(true);
                mIndoorLock = true;
            }
        }
    };

    private IAWayfindingRequest mWayfindingDestination;
    private IAWayfindingListener mWayfindingListener = new IAWayfindingListener() {
        @Override
        public void onWayfindingUpdate(IARoute route) {
            mRoute = route;

            boolean hasArrived;
            if (route.getLegs().size() == 0) {
                hasArrived = false;
            } else {
                final double FINISH_THRESHOLD_METERS = 8.0;
                double routeLength = 0;
                for (IARoute.Leg leg : route.getLegs())
                    routeLength += leg.getLength();

                hasArrived = routeLength < FINISH_THRESHOLD_METERS;
            }

            if (hasArrived) {
                mRoute = null;
                mWayfindingDestination = null;
                mIALocationManager.removeWayfindingUpdates();
            }

            updateRoute();
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        mIALocationManager = IALocationManager.create(this);
        mIALocationManager.registerRegionListener(mRegionListener);

        // Obtain the SupportMapFragment and get notified when the map is ready to be used.
        SupportMapFragment mapFragment = (SupportMapFragment) getSupportFragmentManager()
                .findFragmentById(R.id.map);
        mapFragment.getMapAsync(this);

        LocationManager locationManager = (LocationManager) getSystemService(Context.LOCATION_SERVICE);
        if (locationManager != null) {
            locationManager.requestLocationUpdates(LocationManager.GPS_PROVIDER, 0, 0, this);
            locationManager.requestLocationUpdates(LocationManager.NETWORK_PROVIDER, 0, 0, this);
        }
    }

    @Override
    public void onMapReady(GoogleMap googleMap) {
        mMap = googleMap;
        mMap.setMyLocationEnabled(true);
        mMap.getUiSettings().setMyLocationButtonEnabled(false);

        mMap.setOnMapClickListener(new GoogleMap.OnMapClickListener() {
            public void onMapClick(LatLng pos) {
                if (mMap != null) {
                    mWayfindingDestination = new IAWayfindingRequest.Builder()
                        .withFloor(mFloorLevel)
                        .withLatitude(pos.latitude)
                        .withLongitude(pos.longitude)
                        .build();

                    mIALocationManager.requestWayfindingUpdates(mWayfindingDestination, mWayfindingListener);

                    if (mDestinationMarker == null)
                        mDestinationMarker = mMap.addMarker(new MarkerOptions()
                            .position(pos)
                            .icon(BitmapDescriptorFactory.defaultMarker(BitmapDescriptorFactory.HUE_BLUE)));
                    else
                        mDestinationMarker.setPosition(pos);
                }
            }
        });
    }

    @Override
    public void onLocationChanged(Location location) {
        // Empty.
    }

    @Override
    public void onStatusChanged(String provider, int status, Bundle extras) {
        // Empty.
    }
    @Override
    public void onProviderEnabled(String provider) {
        // Empty.
    }

    @Override
    public void onProviderDisabled(String provider) {
        // Empty.
    }

    @Override
    protected void onResume() {
        super.onResume();

        mIALocationManager.requestLocationUpdates(IALocationRequest.create(), mListener);
        mIALocationManager.registerRegionListener(mRegionListener);

        if (mWayfindingDestination != null)
            mIALocationManager.requestWayfindingUpdates(mWayfindingDestination, mWayfindingListener);
    }

    @Override
    protected void onPause() {
        super.onPause();

        if (mIALocationManager != null) {
            mIALocationManager.removeLocationUpdates(mListener);
            mIALocationManager.registerRegionListener(mRegionListener);

            if (mWayfindingDestination != null)
                mIALocationManager.removeWayfindingUpdates();
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();

        MapFragment mapFragment = (MapFragment) this.getFragmentManager().findFragmentById(R.id.map);
        if (mapFragment != null)
            this.getFragmentManager().beginTransaction().remove(mapFragment).commit();

        mIALocationManager.destroy();
    }

    private void updateRoute() {
        for (Polyline pl : mPolylines)
            pl.remove();
        mPolylines.clear();

        if (mRoute == null)
            return;

        for (IARoute.Leg leg : mRoute.getLegs()) {
            if (leg.getEdgeIndex() == null)
                continue;

            PolylineOptions polylineOptions = new PolylineOptions()
                    .add(new LatLng(leg.getBegin().getLatitude(), leg.getBegin().getLongitude()))
                    .add(new LatLng(leg.getEnd().getLatitude(), leg.getEnd().getLongitude()));

            if (leg.getBegin().getFloor() == mFloorLevel && leg.getEnd().getFloor() == mFloorLevel)
                polylineOptions.color(0xFF0000FF);
            else
                polylineOptions.color(0x300000FF);

            mPolylines.add(mMap.addPolyline(polylineOptions));
        }
    }
}
