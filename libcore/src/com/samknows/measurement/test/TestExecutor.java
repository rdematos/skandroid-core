package com.samknows.measurement.test;

import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.HashMap;
import java.util.concurrent.ExecutionException;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import android.app.Notification;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.location.Location;
import android.net.ConnectivityManager;
import android.net.NetworkInfo;
import android.net.wifi.ScanResult;
import android.net.wifi.WifiInfo;
import android.net.wifi.WifiManager;
import android.util.Log;
import android.util.Pair;

import com.samknows.libcore.SKLogger;
import com.samknows.libcore.SKConstants;
import com.samknows.libcore.SKOperators;
import com.samknows.libcore.SKOperators.ISKQueryCompleted;
import com.samknows.libcore.SKOperators.SKOperators_Return;
import com.samknows.libcore.SKOperators.SKThrottledQueryResult;
import com.samknows.measurement.SK2AppSettings;
import com.samknows.measurement.SKApplication;
import com.samknows.libcore.R;
import com.samknows.measurement.schedule.TestDescription.*;
import com.samknows.measurement.environment.DCSData;
import com.samknows.measurement.environment.LocationData;
import com.samknows.measurement.environment.NetworkData;
import com.samknows.measurement.environment.NetworkDataCollector;
import com.samknows.measurement.schedule.TestDescription;
import com.samknows.measurement.schedule.TestGroup;
import com.samknows.measurement.schedule.ScheduleConfig.LocationType;
import com.samknows.measurement.schedule.condition.ConditionGroup;
import com.samknows.measurement.schedule.condition.ConditionGroupResult;
import com.samknows.measurement.schedule.datacollection.BaseDataCollector;
import com.samknows.measurement.schedule.datacollection.LocationDataCollector;
import com.samknows.measurement.storage.DBHelper;
import com.samknows.measurement.storage.ResultsContainer;
import com.samknows.measurement.storage.StorageTestResult;
import com.samknows.measurement.storage.TestBatch;
import com.samknows.measurement.util.DCSStringBuilder;
import com.samknows.measurement.util.SKDateFormat;
import com.samknows.tests.Param;
import com.samknows.tests.Test;
import com.samknows.tests.TestFactory;

public class TestExecutor {
	private static final String JSON_SUBMISSION_TYPE = "submission_type";
	private static final String TAG = TestExecutor.class.getName();
	private TestContext tc;
	private Test executingTest;
	private long lastTestBytes;
	private long accumulatedTestBytes;
	private Thread startThread = null;
	private ResultsContainer rc;
	
	public Test getExecutingTest() {
		return executingTest;
	}
	
	public long getAccumulatedTestBytes() {
		return accumulatedTestBytes;
	}
	
	public String getInternalNameOfExecutingTest()
	{
		return executingTest.getOutputFields()[0];
	}
	
	public ResultsContainer getResultsContainer() {
		return rc;
	}
	
	public SKThrottledQueryResult mThrottledQueryResult = null;
	String mpThrottleResponse = "no throttling";
	
	private JSONArray accumulatedNetworkTypeLocationMetrics = new JSONArray();
	
	public TestContext getTestContext() {
		return tc;
	}
	
	
	private void sAddPassiveLocationMetricForTestResult(JSONObject jsonResult) {
		Pair<Location, LocationType> lastKnownPair = LocationDataCollector.sGetLastKnownLocation(getTestContext());
		if (lastKnownPair == null) {
			// Nothing known - don't store a passive metric, simply return instead...
			SKLogger.sAssert(getClass(),  false);
			return;
		}
		
		Location lastKnownLocation = lastKnownPair.first;

		if (lastKnownLocation == null) {
			// Nothing known - don't store a passive metric, simply return instead...
			return;
		}
		LocationType lastKnownLocationType = lastKnownPair.second;
		
		LocationData locationData = new LocationData(true, lastKnownLocation, lastKnownLocationType);

		// The following should only ever return a List<JSONObject> containing one item!
		List<JSONObject> passiveMetrics = locationData.convertToJSON();
		int items = passiveMetrics.size();
		SKLogger.sAssert(StorageTestResult.class, items == 1);

		int i;
		for (i = 0; i < items; i++) {
			JSONObject item = passiveMetrics.get(i);
			try {
				// Overwrite the date/time fields...
//				ResultsContainer resultsContainer = getResultsContainer();
//				JSONObject theTest = resultsContainer.getJSONArrayForTestId(getInternalNameOfExecutingTest());
				long timestamp = 0;
				try{
					timestamp = jsonResult.getLong(DCSData.JSON_TIMESTAMP);
				}catch(Exception e){
					int hahah = 1;
				}
				
				item.put("type", "location");
				item.put(DCSData.JSON_TIMESTAMP, timestamp);
				String datetime = jsonResult.getString(DCSData.JSON_DATETIME);
				item.put(DCSData.JSON_DATETIME,  datetime); // new java.util.Date(timestamp * 1000L).toString());
				
         		accumulatedNetworkTypeLocationMetrics.put(item);
			} catch (JSONException e) {
				SKLogger.sAssert(StorageTestResult.class, false);
			}
		}
	}
	
	private void sAddPassiveNetworkTypeMetricForTestResult(JSONObject jsonResult) { 
		
		NetworkDataCollector networkDataCollector = new NetworkDataCollector(getTestContext().getContext());
		
		NetworkData networkData = networkDataCollector.collect();
		
		// The following should only ever return a List<JSONObject> containing one item!
		List<JSONObject> passiveMetrics = networkData.convertToJSON();
		int items = passiveMetrics.size();
		SKLogger.sAssert(StorageTestResult.class, items == 1);

		int i;
		for (i = 0; i < items; i++) {
			JSONObject item = passiveMetrics.get(i);
			try {
				// Overwrite the date/time fields...
				long timestamp = jsonResult.getLong(DCSData.JSON_TIMESTAMP);
				item.put(DCSData.JSON_TIMESTAMP, timestamp);
				String datetime = jsonResult.getString(DCSData.JSON_DATETIME);
				item.put(DCSData.JSON_DATETIME,  datetime); // new java.util.Date(timestamp * 1000L).toString());
				
         		accumulatedNetworkTypeLocationMetrics.put(item);
			} catch (JSONException e) {
				SKLogger.sAssert(StorageTestResult.class, false);
			}
		}

	}
		
	public TestExecutor(TestContext tc) {
		super();
		this.tc = tc;
		
		accumulatedTestBytes = 0L;
		
		rc = new ResultsContainer();
		
		if (SKApplication.getAppInstance().isThrottleQuerySupported() == false) {
			// No throttled query!
			mThrottledQueryResult = null;
			mpThrottleResponse = "no throttling";
		} else {
			// Fire-off a Throttle test, capture it, and submit when done!
			SKOperators operators = SKOperators.getInstance(tc.getContext());
			mpThrottleResponse = "timeout";
			mThrottledQueryResult = operators.fireThrottledWebServiceQueryWithCallback(new ISKQueryCompleted() {

				@Override
				public void onQueryCompleted(Exception e, long responseCode,
						String responseDataAsString) {
					SKLogger.sAssert(getClass(), mThrottledQueryResult.returnCode == SKOperators_Return.SKOperators_Return_FiredThrottleQueryAwaitCallback);

					if (e == null) {
						Log.d(TestExecutor.class.getName(), "DEBUG - throttle query success, responseCode=(" + responseCode + "), responseDataAsString=(" + responseDataAsString + ")");

						if (responseCode == 200) {
							if (responseDataAsString.equals("YES")) {
								mpThrottleResponse = "throttled";
							} else if ( responseDataAsString.equals("NO")) {
								mpThrottleResponse = "non-throttled";
							} else {
								SKLogger.sAssert(getClass(), false);
								mpThrottleResponse = "error";
							}
						} else {
							SKLogger.sAssert(getClass(), false);

							// http://en.wikipedia.org/wiki/List_of_HTTP_status_codes
							if (    (responseCode == 408) // Request Timeout
									|| (responseCode == 504) // Gateway Timeout
									|| (responseCode == 524) // Timeout
									|| (responseCode == 598) // timeout
									|| (responseCode == 599) // timeout
									)
							{
								mpThrottleResponse = "timeout";
							} else {
								mpThrottleResponse = "error";
							}
						}
					} else {
						Log.d(TestExecutor.class.getName(), "DEBUG - throttle query error, responseCode=(" + responseCode + "), responseDataAsString=(" + responseDataAsString + ")");
						//SKLogger.sAssert(getClass(), false);
						mpThrottleResponse = "error";
					}
				}
			});

			if (mThrottledQueryResult.returnCode == SKOperators_Return.SKOperators_Return_NoThrottleQuery) {
				mpThrottleResponse = "no throttling";
			}
		}

		// This is required to be stored in the test Context; otherwise,
		// we cannot capture information on failed Conditions!
		tc.resultsContainer = rc;

	}
	
	public void addRequestedTest(TestDescription td){
		rc.addRequestedTest(td);
	}
	
	public ConditionGroupResult execute(ConditionGroup cg){ 
		ConditionGroupResult ret = new ConditionGroupResult();
		if(cg == null){
			return ret;
		}
		try{
			ConditionGroupResult c = (ConditionGroupResult ) cg.testBefore(tc).get(); 
			ret.add(c);
		//Treat exceptions as failures	
		} catch(ExecutionException ee){
			SKLogger.e(this, "Error in running test condition: " + ee.getMessage());
			ret.isSuccess=false;
		} catch(InterruptedException ie){
			SKLogger.e(this, "Error in running test condition: " + ie.getMessage());
			ret.isSuccess=false;
		}
		return ret;
	
	}
	
	public ConditionGroupResult execute(ConditionGroup cg, TestDescription td) {
		showNotification(tc.getString(R.string.ntf_checking_conditions));
		ConditionGroupResult result = execute(cg);

		if (result.isSuccess || result.isFailQuiet()) {
			executeTest(td, result);
		}
		SKLogger.d(TAG, "result test: " + (result.isSuccess ? "OK" : "FAIL"));

		if (result.isSuccess && cg != null) {
			ConditionGroupResult cgr = cg.testAfter(tc);
			result.add(cgr);
			rc.addCondition(result.json_results);
		}

		if (cg != null) {
			cg.release(tc);
		}

		SKLogger.d(this, "conditionGroup:execute - rc.getJSON()=" + rc.getJSON().toString());

		// TestResultsManager.saveResult(tc.getServiceContext(),
		// result.results);
		cancelNotification();
		return result;
	}

	public Test executeTest(TestDescription td, ConditionGroupResult result) {
		
		try {
			List<Param> params = tc.paramsManager.prepareParams(td.params);

			executingTest = TestFactory.create(td.type, params);
			if (executingTest != null) {
				SKLogger.d(TestExecutor.class, "start to execute test: " + td.displayName);
			
				String displayName = td.displayName;
				boolean bShowNotification = true;
				if (td.type.equalsIgnoreCase(TestFactory.CLOSESTTARGET)) {
					if (SKApplication.getAppInstance().getDoesAppDisplayClosestTargetInfo() == false) {
						// Do NOT show closest target report!
    				  bShowNotification = false;
					}
				} else if (td.type.equals(TestFactory.LATENCY)) {
					displayName = tc.getString(R.string.latency);
				} else if (td.type.equals(TestFactory.DOWNSTREAMTHROUGHPUT)) {
					displayName = tc.getString(R.string.download);
				} else if (td.type.equals(TestFactory.UPSTREAMTHROUGHPUT)) {
					displayName = tc.getString(R.string.upload);
				}
				
				if (bShowNotification) {
					showNotification(tc.getString(R.string.ntf_running_test) + displayName);
				}
				
				//execute the test in a new thread and kill it if it doesn't terminate after
				//Constants.WAIT_TEST_BEFORE_ABORT
				Thread t = new Thread(new Runnable() {
					@Override
					public void run() {
						executingTest.execute();
					}
				});
				t.start();
				t.join(SKConstants.WAIT_TEST_BEFORE_ABORT);
				if (t.isAlive()) {
					SKLogger.e(this, "Test is still running after "+SKConstants.WAIT_TEST_BEFORE_ABORT/1000+" seconds.");
					t.interrupt();
					t = null;
				} else {
					lastTestBytes = executingTest.getNetUsage();
					accumulatedTestBytes += lastTestBytes;
					result.isSuccess = executingTest.isSuccessful();
					String out = executingTest.getOutputString();
					result.addTestString(out);
					
					// TODO MPC - theJsonResult here, can be used to append the Accumulated results!
					JSONObject jsonResult = executingTest.getJSONResult();
					SKLogger.d("", jsonResult.toString());//TODO remove in production
					sAddPassiveLocationMetricForTestResult(jsonResult);
		      sAddPassiveNetworkTypeMetricForTestResult(jsonResult);
					rc.addTest(jsonResult);
					
					// HACK TO INCLUDE THE JUDPJITTER RESULTS
					if (td.type.equalsIgnoreCase("latency")) {
						String[] judp = executingTest.getOutputFields();
						DCSStringBuilder jjitter = new DCSStringBuilder();
						String jitter = ""+ (Integer.parseInt(judp[5]) - Integer.parseInt(judp[6]));
						String sent = ""+ (Integer.parseInt(judp[9]) + Integer.parseInt(judp[10]));
						String received = ""+ (Integer.parseInt(judp[9]) - Integer.parseInt(judp[10]));
						jjitter.append("JUDPJITTER");
						jjitter.append(judp[1]); // TIMESTAMP
						jjitter.append(judp[2]); // STATUS
						jjitter.append(judp[3]); // TARGET
						jjitter.append(judp[4]); // TARGET IP ADDRESS
						jjitter.append(128); // PACKETSIZE
						jjitter.append(0); // BITRATE
						jjitter.append(0); // DURATION
						jjitter.append(sent); // PACKETS SENT UP
						jjitter.append(sent); // PACKETS SENT DOWN
						jjitter.append(received); // PACKETS RECEIVED UP
						jjitter.append(received); // PACKETS RECEIVED DOWN
						jjitter.append(jitter); // JITTER UP
						jjitter.append(jitter); // JITTER DOWN
						jjitter.append(judp[5]); // AVERAGE RTT
						result.addTestString(jjitter.build());
					}

					if (result.isSuccess) {
						tc.paramsManager.processOutParams(out, td.outParamsDescription);


						HashMap<String, String> last_values = executingTest.getResults();
						if( last_values != null ){
							for (String key : last_values.keySet()) {
								String value = last_values.get(key);
								SKLogger.d(TestExecutor.class, "last_" + key + " " + value);
								SK2AppSettings.getInstance().saveString( "last_" + key, value);
							}
						}
					}

					SKLogger.d(TAG, "finished execution test: " + td.type);
				}
			} else {
				SKLogger.e(TAG, "Can't find test for: " + td.type,
						new RuntimeException());
				SKLogger.sAssert(getClass(), false);
				result.isSuccess = false;
			}
		} catch (Throwable e) {
			SKLogger.e(this, "Error in executing the test. ", e);
			SKLogger.sAssert(getClass(), false);
			result.isSuccess = false;
		} finally {
			cancelNotification();
		}
		
		return executingTest;
	}

	public int getProgress() {
		if (executingTest != null) {
			return executingTest.getProgress();
		}
		return -1;
	}

	@SuppressWarnings("deprecation")
	public void showNotification(String message) {
		String title = SKApplication.getAppInstance().getAppName();

		NotificationManager manager = (NotificationManager) tc
				.getSystemService(Context.NOTIFICATION_SERVICE);
		Notification n = new Notification(R.drawable.icon_notification, message,
				System.currentTimeMillis());
		PendingIntent intent = PendingIntent.getService(tc.getContext(),
				SKConstants.RC_NOTIFICATION, new Intent(),
				PendingIntent.FLAG_UPDATE_CURRENT);
		n.setLatestEventInfo(tc.getContext(), title, message, intent);
		manager.notify(SKConstants.NOTIFICATION_ID, n);
	}

	public void cancelNotification() {
		NotificationManager manager = (NotificationManager) tc
				.getSystemService(Context.NOTIFICATION_SERVICE);
		manager.cancel(SKConstants.NOTIFICATION_ID);
	}

	public void startInBackGround() {
		startThread = new Thread(new Runnable() {
			public void run() {
				start();
			}
		});
		startThread.start();
	}

	public void start() {
		for (BaseDataCollector collector : tc.config.dataCollectors) {
			if (collector.isEnabled)
				collector.start(tc);
		}
	}

	public void stop() {
		if (startThread != null) {
			for (;;) {
				
				try {
					if (!startThread.isAlive()) {
						break;
					}
					
					startThread.join(100);
					
				} catch (InterruptedException ie) {
					SKLogger.e(this, "Ignore InterruptedException while waiting for the start thread to finish");
					SKLogger.sAssert(getClass(), false);
				}
			}
		}
		
		for (BaseDataCollector collector : tc.config.dataCollectors) {
			if (collector.isEnabled) {
				collector.stop(tc);
				// TestResultsManager.saveResult(tc.getServiceContext(),
				// collector.getOutput());
				rc.addMetric(collector.getJSONOutput());
			}
		}
		
	}
	
	//private long mBatchId = -1;

	public ConditionGroupResult executeGroup(TestGroup tg) {
		long startTime = System.currentTimeMillis();
		List<TestDescription> tds = new ArrayList<TestDescription>();
		for (SCHEDULE_TEST_ID test_id : tg.testIds) {
			tds.add(tc.config.findTestById(test_id));
		}
		ConditionGroup cg = tc.config.getConditionGroup(tg.conditionGroupId);
		showNotification(tc.getString(R.string.ntf_checking_conditions));
		ConditionGroupResult result = execute(cg);
		
		
		//Run tests only if the conditions are met
		if(result.isSuccess){
			for (TestDescription td : tds) {
				executeTest(td, result);
			}
		}
		List<JSONObject> testsResults = new ArrayList<JSONObject>();
		for (String out : result.results) {
			List<JSONObject> theResult = com.samknows.measurement.storage.StorageTestResult
					.testOutput(out, this);
			if (theResult != null) {
				testsResults.addAll(theResult);
			}
		}

		if (cg != null) {
			ConditionGroupResult cgr = cg.testAfter(tc);
			result.add(cgr);
			rc.addCondition(result.json_results);
			cg.release(tc);
		}
		List<JSONObject> passiveMetrics = new ArrayList<JSONObject>();
		for (BaseDataCollector c : tc.config.dataCollectors) {
			if (c.isEnabled) {
				for (JSONObject o : c.getPassiveMetric()) {
					passiveMetrics.add(o);
				}
			}
		}
		JSONObject batch = new JSONObject();
		try {
			batch.put(TestBatch.JSON_DTIME, startTime);
			batch.put(TestBatch.JSON_RUNMANUALLY, "0");
		} catch (JSONException je) {
			SKLogger.e(this,
					"Error in creating test batch object: " + je.getMessage());
		}
		DBHelper db = new DBHelper(tc.getContext());
	
		//SKLogger.sAssert(getClass(), mBatchId == -1);
		//mBatchId = 
		db.insertTestBatch(batch, testsResults, passiveMetrics);
		
		cancelNotification();
		return result;

	}

	public ConditionGroupResult executeBackgroundTestGroup(long groupId) {
		TestGroup tg = tc.config.findBackgroundTestGroup(groupId);
		if (tg == null) {
			SKLogger.e(this, "can not find background test group for id: " + groupId);
		} else {
			return executeGroup(tg);
		}

		return new ConditionGroupResult();
	}

	public ConditionGroupResult execute(long testId) {
		TestDescription td = tc.config.findTest(testId);
		if (td != null) {
			ConditionGroup cg = tc.config
					.getConditionGroup(td.conditionGroupId);
			return execute(cg, td);
		} else {
			SKLogger.e(this, "can not find test for id: " + testId);
		}
		return new ConditionGroupResult();
	}
	
//	public void save(String type, long batchId) {
//		SKLogger.sAssert(getClass(),  mBatchId == -1);
//		mBatchId = batchId;
//		
//    	save(type);
//	}

  // http://stackoverflow.com/questions/5485759/android-how-to-determine-a-wifi-channel-number-used-by-wifi-ap-network
  public static int convertFrequencyToChannel(int freq) {
    if (freq >= 2412 && freq <= 2484) {
      return (freq - 2412) / 5 + 1;
    } else if (freq >= 5170 && freq <= 5825) {
      return (freq - 5170) / 5 + 34;
    }

    return -1;
  }

	public void save(String type, long batchId) {
        //mBatchId = batchId;
		
		//SKLogger.sAssert(getClass(), mBatchId != -1);
		
		rc.addExtra(JSON_SUBMISSION_TYPE, type);
		rc.addExtra("batch_id", String.valueOf(batchId));

		try {
			int accumulatedNetworkTypeLocationMetricCount = accumulatedNetworkTypeLocationMetrics.length();
			int i;
			for (i = 0; i < accumulatedNetworkTypeLocationMetricCount; i++) {
				JSONObject object = accumulatedNetworkTypeLocationMetrics.getJSONObject(i);
				// The following can be useful for debugging!
				// object.put("DEBUG_TAG", "DEBUG_TAG");
				rc.addMetric(object);
			}

			if ( (mThrottledQueryResult != null) &&
   			     (mThrottledQueryResult.returnCode != SKOperators_Return.SKOperators_Return_NoThrottleQuery)
			  )
			{
				JSONObject throttleResponseMetric = new JSONObject();
				throttleResponseMetric.put("type", "carrier_status");
				throttleResponseMetric.put("timestamp", mThrottledQueryResult.timestamp);
				throttleResponseMetric.put("datetime", mThrottledQueryResult.datetimeUTCSimple);
				throttleResponseMetric.put("carrier", mThrottledQueryResult.carrier);
				throttleResponseMetric.put("status", mpThrottleResponse);
				rc.addMetric(throttleResponseMetric);
			}


      // Add WIFI metrics!
      Context context = SKApplication.getAppInstance();
      //TelephonyManager mTelManager = (TelephonyManager) context.getSystemService(Context.TELEPHONY_SERVICE);
      ConnectivityManager connManager = (ConnectivityManager) context.getSystemService(Context.CONNECTIVITY_SERVICE);
      WifiManager wifiManager=(WifiManager)context.getSystemService(Context.WIFI_SERVICE);
      if (connManager != null && wifiManager != null) {
        NetworkInfo netInfo = connManager.getActiveNetworkInfo();
        WifiInfo wifiInfo=wifiManager.getConnectionInfo();
        if (netInfo != null && wifiInfo != null) {
          if (netInfo.getType() == ConnectivityManager.TYPE_WIFI) {

            JSONObject wifiStateMetric = new JSONObject();
            wifiStateMetric.put("type", "wifistate");
            Date now = new Date();
            wifiStateMetric.put("datetime", SKDateFormat.sGetDateAsIso8601String(now));
            wifiStateMetric.put("timestamp", String.valueOf(now.getTime()));
            wifiStateMetric.put("rssi", wifiInfo.getRssi());

            List<ScanResult> results=null;

            try {
              results = wifiManager.getScanResults();
            } catch (SecurityException e) {
              // This has been seen on a very small set of devices...
              SKLogger.sAssert(false);
            }

            if (results != null) {

              // The SSID might be returned with "" around it!
              String wifiInfoSSID = wifiInfo.getSSID().replace("\"", "");
              boolean bFoundWifi = false;
              for (ScanResult scanResult : results) {
                String scanResultSSID = scanResult.SSID;
                if (scanResultSSID.equals(wifiInfoSSID)) {
                  bFoundWifi = true;
                  wifiStateMetric.put("frequency", scanResult.frequency);
                  wifiStateMetric.put("channel", convertFrequencyToChannel(scanResult.frequency));
                  break;
                }
              }
              SKLogger.sAssert(bFoundWifi);
            }
            wifiStateMetric.put("linkspeed", wifiInfo.getLinkSpeed());
            rc.addMetric(wifiStateMetric);
          }
        }
      }

    } catch (JSONException e) {
			SKLogger.sAssert(getClass(),  false);
		}

		//SKLogger.sAssert(getClass(), mBatchId != -1);
		TestResultsManager.saveResult(tc.getContext(), rc);
	}

	public long getLastTestByte() {
		return lastTestBytes;
	}

}
