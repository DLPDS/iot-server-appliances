/*
 * Copyright (c) 2015, WSO2 Inc. (http://www.wso2.org) All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.wso2.devicemgt.raspberry.agent;

import org.apache.http.HttpResponse;
import org.apache.http.client.HttpClient;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.impl.client.DefaultHttpClient;
import org.apache.http.impl.nio.client.HttpAsyncClients;
import org.apache.log4j.Logger;
import org.wso2.siddhi.core.SiddhiManager;
import org.wso2.siddhi.core.event.Event;
import org.wso2.siddhi.core.stream.input.InputHandler;
import org.wso2.siddhi.core.stream.output.StreamCallback;
import org.wso2.siddhi.core.util.EventPrinter;
import org.apache.http.impl.nio.client.CloseableHttpAsyncClient;
import org.apache.http.concurrent.FutureCallback;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Future;

/**
 * This class reads the sonar reading and injects values
 * to the siddhiEngine for processing on a routine basis
 * also if the siddhiquery is updated the class takes
 * care of re-initializing same.
 */
public class SidhdhiQuery implements Runnable {
    private static final Logger log = Logger.getLogger(SidhdhiQuery.class);
    final AgentConstants constants = new AgentConstants();

    //Bam data push client
    private static PushBamData pushBamData = new PushBamData();
    private static SiddhiManager siddhiManager = new SiddhiManager();

    public static PushBamData getPushBamData() {
        return pushBamData;
    }

    public static SiddhiManager getSiddhiManager() {
        return siddhiManager;
    }

    public static void setSiddhiManager(SiddhiManager siddhiManager) {
        SidhdhiQuery.siddhiManager = siddhiManager;
    }

    //keeps track of bulb status. The start status is assumed as off
    //TODO : pick up current bulb status from a API
    boolean isBulbOn = false;

    public void run() {

        //Initialize Push data client
        PushBamData pushdata = getPushBamData();
        pushdata.initializeDataPublisher();

        //Start the execution plan with pre-defined or previously persisted Siddhi query
        StartExecutionPlan startExecutionPlan = new StartExecutionPlan().invoke();

        while (true) {

            //Check if there is new policy update available
            if (AgentInitializer.isUpdated()) {
                System.out.print("### Policy Update Detected!");
                //Restart execution plan with new query
                restartSiddhi();
                startExecutionPlan = new StartExecutionPlan().invoke();
            }
            InputHandler inputHandler = startExecutionPlan.getInputHandler();

            //Sending events to Siddhi
            try {
                //If sonar URL is present in the config file the program will read stats off the API
                //If not it will look for a file which is also configurable via a property
                String sonarUrl = constants.prop.getProperty("sonar.reading.url");
                String sonarReading = null;
                if (sonarUrl != null) {
                    sonarReading = readSonarData(sonarUrl);
                }
                if (sonarReading == null || sonarReading.equalsIgnoreCase("")) {
                    sonarReading = readFile(constants.prop.getProperty("sonar.reading.file.path"), StandardCharsets.UTF_8);
                }
                log.info("Pushing data to CEP - Sonar : " + sonarReading.trim());
                inputHandler.send(new Object[]{"FIRE_1", Double.parseDouble(sonarReading)});
                Thread.sleep(Integer.parseInt(constants.prop.getProperty("read.interval")));
//                executionPlanRuntime.shutdown();
            } catch (InterruptedException e) {
                e.printStackTrace();
            }
        }
    }

    /**
     * Re-Initialize SiddhiManager
     */
    private void restartSiddhi() {
        siddhiManager.shutdown();
        siddhiManager = new SiddhiManager();
    }

    /**
     * Make http call to specified endpoint with events
     * @param inEvents
     * @param bulbEP
     * @param logText
     */
    private void performHTTPCall(Event[] inEvents, String bulbEP, String logText) {
        if (inEvents != null && inEvents.length > 0) {
            EventPrinter.print(inEvents);
            String url = constants.prop.getProperty(bulbEP);

            CloseableHttpAsyncClient httpclient = null;

                httpclient = HttpAsyncClients.createDefault();
                httpclient.start();
                HttpGet request = new HttpGet(url);
                log.info("Bulb Status : " + logText);
                final CountDownLatch latch = new CountDownLatch(1);
                Future<HttpResponse> future = httpclient.execute(
                        request, new FutureCallback<HttpResponse>() {
                            @Override
                            public void completed(HttpResponse httpResponse) {
                                latch.countDown();
                            }

                            @Override
                            public void failed(Exception e) {
                                latch.countDown();
                            }

                            @Override
                            public void cancelled() {
                                latch.countDown();
                            }
                        }
                );

            try {
                latch.await();
            } catch (InterruptedException e) {
                e.printStackTrace();
            }
        }
    }

    /**
     * Read content from a given file and return as a string
     * @param path
     * @param encoding
     * @return
     */
    static String readFile(String path, Charset encoding) {
        byte[] encoded = new byte[0];
        try {
            encoded = Files.readAllBytes(Paths.get(path));
        } catch (IOException e) {
            log.error("Error reading Sidhdhi query from file.");
        }
        return new String(encoded, encoding);
    }


    /**
     * Read sonar data from API URL
     * @param sonarAPIUrl
     * @return
     */
    private String readSonarData(String sonarAPIUrl) {
        HttpClient client = new DefaultHttpClient();
        HttpGet request = new HttpGet(sonarAPIUrl);
        String responseStr = null;
        try {
            HttpResponse response = client.execute(request);
            log.debug("Response Code : " + response);
            InputStream input = response.getEntity().getContent();
            BufferedReader br = new BufferedReader(new InputStreamReader(input, "UTF-8"));
            responseStr = String.valueOf(br.readLine());
            br.close();

        } catch (IOException e) {
            //log.error("Exception encountered while trying to make get request.");
            log.error("Error while reading sonar reading from file!");
            return responseStr;
        }
        return responseStr;
    }

    /**
     * Initialize SiddhiExecution plan
     */
    private class StartExecutionPlan {
        private InputHandler inputHandler;

        public InputHandler getInputHandler() {
            return inputHandler;
        }

        public StartExecutionPlan invoke() {
            String executionPlan;
            executionPlan = readFile(constants.prop.getProperty("execution.plan.file.location"), StandardCharsets.UTF_8);

            //Generating runtime
            siddhiManager.addExecutionPlan(executionPlan);

            siddhiManager.addCallback("bulbOnStream", new StreamCallback() {
                @Override
                public void receive(Event[] events) {
                    System.out.println("Bulb on Event Fired!");
                    if (events.length > 0) {
                        if (!isBulbOn) {
                            performHTTPCall(events, "bulb.on.api.endpoint", "Bulb Switched on!");
                            System.out.println("#### Performed HTTP call! ON.");
                            pushBamData.publishData(constants.prop.getProperty("device.id"),
                                                    constants.prop.getProperty("device.type"),
                                                    constants.prop.getProperty("device.user"),
                                                    "BULB SWITCHED ON");
                            isBulbOn = true;
                        }
                    }
                }
            });

            siddhiManager.addCallback("bulbOffStream", new StreamCallback() {
                @Override
                public void receive(Event[] inEvents) {
                    System.out.println("Bulb off Event Fired");
                    if (isBulbOn) {
                        performHTTPCall(inEvents, "bulb.off.api.endpoint", "Bulb Switched off!");
                        System.out.println("#### Performed HTTP call! OFF.");
                        pushBamData.publishData(constants.prop.getProperty("device.id"),
                                                constants.prop.getProperty("device.type"),
                                                constants.prop.getProperty("device.user"),
                                                "BULB SWITCHED OFF");
                        isBulbOn = false;
                    }
                }

            });

            //Retrieving InputHandler to push events into Siddhi
            inputHandler = siddhiManager.getInputHandler("fireAlarmEventStream");

            //Starting event processing
            System.out.println("Execution Plan Started!");
            return this;
        }
    }
}
