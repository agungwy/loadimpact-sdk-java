package com.loadimpact.eval;

import com.loadimpact.ApiTokenClient;
import com.loadimpact.RunningTestListener;
import com.loadimpact.exception.AbortTest;
import com.loadimpact.exception.ApiException;
import com.loadimpact.resource.Test;
import com.loadimpact.resource.TestConfiguration;
import com.loadimpact.resource.configuration.LoadScheduleStep;
import com.loadimpact.resource.testresult.StandardMetricResult;
import com.loadimpact.util.Debug;
import com.loadimpact.util.ListUtils;
import com.loadimpact.util.StringUtils;

import java.net.URL;
import java.util.List;

import static com.loadimpact.resource.testresult.StandardMetricResult.Metrics;

/**
 * Monitors a running load test and evaluates the thresholds.
 *
 * @author jens
 */
public class LoadTestListener implements RunningTestListener {
    private final Debug                  debug;
    private final LoadTestLogger         logger;
    private final LoadTestParameters     params;
    private       LoadTestState          state;
    private       int                    totalTimeMinutes;
    private       Long                   startTime;
    private       Double                 lastPercentage;
    private       URL                    resultsUrl;
    private final Threshold[]            thresholds;
    private final LoadTestResultListener loadTestResultListener;


    public LoadTestListener(LoadTestParameters params, LoadTestLogger logger, LoadTestResultListener loadTestResultListener) {
        this.debug = new Debug(this);
        this.params = params;
        this.logger = logger;
        this.loadTestResultListener = loadTestResultListener;
        this.state = LoadTestState.notStarted;
        this.lastPercentage = -1D;

        BoundedDroppingQueue.setDefaultSize(params.getDelaySize() /*params.get(Constants.delaySize_key, 1)*/);

        this.thresholds = params.getThresholds() /*new ArrayList<Threshold>()*/;
//        for (int k = 1; k <= Constants.thresholdCount; ++k) {
//            int thresholdValue = params.get(Constants.thresholdValueKey(k), -1);
//            if (thresholdValue >= 0) {
//                Metrics metric = params.get(Constants.thresholdMetricKey(k), Metrics.USER_LOAD_TIME);
//                Operator        operator = params.get(Constants.thresholdOperatorKey(k), Operator.greaterThan);
//                LoadTestResult  result   = params.get(Constants.thresholdResultKey(k), LoadTestResult.unstable);                
//                this.thresholds.add(new Threshold(k, metric, operator, thresholdValue, result));
//            }
//        }
    }

    @SuppressWarnings("UnusedParameters")
    public void onSetup(TestConfiguration configuration, ApiTokenClient client) {
        if (params.isLogReplies() /*params.get(Constants.logReplies_key, false)*/)
            debug.print("test-configuration: %s", configuration.toString());

        totalTimeMinutes = ListUtils.reduce(configuration.loadSchedule, 0, new ListUtils.ReduceClosure<Integer, LoadScheduleStep>() {
            @Override
            public Integer eval(Integer sum, LoadScheduleStep s) {
                return sum + s.duration;
            }
        });
    }

    @Override
    public void onProgress(Test test, ApiTokenClient client) {
        if (params.isLogReplies() /*params.get(Constants.logReplies_key, false)*/) debug.print(test.toString());

        LoadTestState lastState = state;
        state = state.moveToNext(test.status);
//        System.out.printf("**** state: %s --> %s %n", lastState.toString(), state.toString());
        if (state.isActive()) {
            if (startTime == null) startTime = now();

            Double percentage = getProgressPercentage(test, client);
            if (percentage != null && lastPercentage < percentage) {
                lastPercentage = percentage;
                logger.message("Running: %s (~ %.1f minutes remaining)",
                        StringUtils.percentageBar(percentage),
                        totalTimeMinutes * (100D - percentage) / 100D
                );
            }
        } else {
            if (state != lastState) logger.message("Load test state: %s", state);
        }

        if (resultsUrl == null && StringUtils.startsWith(test.publicUrl, "http")) {
            resultsUrl = test.publicUrl;
            logger.message("Start sending load traffic [%d] %s", test.id, test.title);
            logger.message("Follow the test progress at URL %s", test.publicUrl);
        }

        if (state.isBeforeCheckingThresholds()) {
            DelayUnit delayUnit = params.getDelayUnit() /*DelayUnit.valueOf(params.get(Constants.delayUnit_key, DelayUnit.seconds.name()))*/;
            int delayValue = params.getDelayValue() /*params.get(Constants.delayValue_key, 0)*/;

            String reason = "";
            if (DelayUnit.seconds == delayUnit) {
                state = state.moveToNext(test.status, (startTime + delayValue * 1000) < now());
                reason = String.format("Passed %d seconds after running test start (current=%d seconds)", delayValue, (now() - startTime) / 1000);
            } else if (DelayUnit.users == delayUnit) {
                List<? extends StandardMetricResult> results = client.getStandardMetricResults(test.id, Metrics.CLIENTS_ACTIVE, null, null);
                int usersCount = results.isEmpty() ? 0 : ListUtils.last(results).value.intValue();
                state = state.moveToNext(test.status, delayValue < usersCount);
                reason = String.format("Passed %d users (current=%d users)", delayValue, usersCount);
            }

            if (state.isCheckingThresholds()) logger.message("Start checking thresholds: %s", reason);
        }

        if (state.isCheckingThresholds()) {
            for (Threshold t : thresholds) {
                List<? extends StandardMetricResult> metricValues = client.getStandardMetricResults(test.id, t.getMetric(), null, null);
                t.accumulate(metricValues);
                debug.print("Checking %s", t);

                if (t.isExceeded()) {
                    loadTestResultListener.markAs(t.getResult(), t.getReason());
                    debug.print("Threshold %d EXCEEDED: Build marked %s. Reason: %s", t.getId(), t.getResult().getDisplayName(), t.getReason());

                    if (loadTestResultListener.isFailure() && params.isAbortAtFailure() /*params.get(Constants.abortAtFailure_key, false)*/) {
                        throw new AbortTest();
                    }
                }
            }

            state = state.moveToNext(test.status, lastPercentage >= 100D);
            if (state != lastState && !state.isCheckingThresholds()) logger.message("Load test state: %s", state);
        }
    }

    @Override
    public void onSuccess(Test test) {
        logger.message("Load test completed");
    }

    @Override
    public void onFailure(Test test) {
        logger.failure("Load test failed: " + test.status);
    }

    @Override
    public void onAborted() {
        logger.failure("Load test requested to be aborted");
        loadTestResultListener.stopBuild();
    }

    @Override
    public void onError(ApiException e) {
        logger.failure("Load test internal error: " + e);
        loadTestResultListener.markAs(LoadTestResult.error, e.toString());
        loadTestResultListener.stopBuild();
    }

    private Double getProgressPercentage(Test test, ApiTokenClient client) {
        List<? extends StandardMetricResult> progress = client.getStandardMetricResults(test.id, Metrics.PROGRESS_PERCENT_TOTAL, null, null);
        if (progress == null || progress.isEmpty()) return null;
        return ListUtils.last(progress).value.doubleValue();
    }

    /**
     * Returns the current time stamp.
     *
     * @return now
     */
    private long now() {
        return System.currentTimeMillis();
    }

}
