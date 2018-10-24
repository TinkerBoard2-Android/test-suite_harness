/*
 * Copyright (C) 2015 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.android.compatibility.common.tradefed.targetprep;

import com.android.annotations.VisibleForTesting;
import com.android.compatibility.common.tradefed.build.CompatibilityBuildHelper;
import com.android.compatibility.common.util.DynamicConfig;
import com.android.compatibility.common.util.DynamicConfigHandler;
import com.android.ddmlib.Log;
import com.android.tradefed.build.IBuildInfo;
import com.android.tradefed.config.IConfiguration;
import com.android.tradefed.config.IConfigurationReceiver;
import com.android.tradefed.config.Option;
import com.android.tradefed.config.OptionClass;
import com.android.tradefed.device.DeviceNotAvailableException;
import com.android.tradefed.device.ITestDevice;
import com.android.tradefed.log.LogUtil.CLog;
import com.android.tradefed.targetprep.BaseTargetPreparer;
import com.android.tradefed.targetprep.BuildError;
import com.android.tradefed.targetprep.ITargetCleaner;
import com.android.tradefed.targetprep.TargetSetupError;
import com.android.tradefed.testtype.suite.TestSuiteInfo;
import com.android.tradefed.util.FileUtil;
import com.android.tradefed.util.StreamUtil;

import org.json.JSONException;
import org.xmlpull.v1.XmlPullParserException;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.util.List;

/**
 * Pushes dynamic config files from config repository
 */
@OptionClass(alias="dynamic-config-pusher")
public class DynamicConfigPusher extends BaseTargetPreparer
        implements IConfigurationReceiver, ITargetCleaner {
    public enum TestTarget {
        DEVICE,
        HOST
    }

    /* API Key for compatibility test project, used for dynamic configuration. */
    private static final String API_KEY = "AIzaSyAbwX5JRlmsLeygY2WWihpIJPXFLueOQ3U";
    private static final String LOG_TAG = DynamicConfigPusher.class.getSimpleName();

    @Option(name = "api-key", description = "API key for for dynamic configuration.")
    private String mApiKey = API_KEY;

    @Option(name = "cleanup", description = "Whether to remove config files from the test " +
            "target after test completion.")
    private boolean mCleanup = true;

    @Option(name = "config-url", description = "The url path of the dynamic config. If set, " +
            "will override the default config location defined in CompatibilityBuildProvider.")
    private String mConfigUrl = "https://androidpartner.googleapis.com/v1/dynamicconfig/" +
            "suites/{suite-name}/modules/{module}/version/{version}?key={api-key}";

    @Option(name="config-filename", description = "The module name for module-level " +
            "configurations, or the suite name for suite-level configurations", mandatory = true)
    private String mModuleName;

    @Option(name = "target", description = "The test target, \"device\" or \"host\"",
            mandatory = true)
    private TestTarget mTarget;

    @Option(name = "version", description = "The version of the configuration to retrieve " +
            "from the server, e.g. \"1.0\". Defaults to suite version string.")
    private String mVersion;

    // Options for getting the dynamic file from resources.
    @Option(name = "extract-from-resource",
            description = "Whether to look for the local dynamic config inside the jar resources "
                + "or on the local disk.")
    private boolean mExtractFromResource = false;

    @Option(name = "dynamic-resource-name",
            description = "When using --extract-from-resource, this option allow to specify the "
                + "resource name, instead of the module name for the lookup. File will still be "
                + "logged under the module name.")
    private String mResourceFileName = null;

    @Option(name = "dynamic-config-name",
            description = "The dynamic config name for module-level configurations, or the "
                + "suite name for suite-level configurations.")
    private String mDynamicConfigName = null;

    private String mDeviceFilePushed;

    private IConfiguration mConfiguration = null;

    void setModuleName(String moduleName) {
        mModuleName = moduleName;
    }

    /** {@inheritDoc} */
    @Override
    public void setConfiguration(IConfiguration configuration) {
        mConfiguration = configuration;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void setUp(ITestDevice device, IBuildInfo buildInfo) throws TargetSetupError, BuildError,
            DeviceNotAvailableException {

        CompatibilityBuildHelper buildHelper = new CompatibilityBuildHelper(buildInfo);

        File localConfigFile = getLocalConfigFile(buildHelper, device);

        if (mVersion == null) {
            mVersion = buildHelper.getSuiteVersion();
        }

        // TODO(b/117746848): To find a way to share the configuration object across shard.
        String suiteName = (mConfiguration != null) ?
                getSuiteName() : TestSuiteInfo.getInstance().getName();
        String apfeConfigInJson = null;
        String requestUrl = null;

        try {
            requestUrl = mConfigUrl.replace("{suite-name}", suiteName)
                    .replace("{module}", mModuleName)
                    .replace("{version}", mVersion)
                    .replace("{api-key}", mApiKey);
            java.net.URL request = new URL(requestUrl);
            apfeConfigInJson = StreamUtil.getStringFromStream(request.openStream());
        } catch (IOException e) {
            CLog.w(e);
        }

        // Use DynamicConfigHandler to merge local and service configuration into one file
        File hostFile = mergeConfigFiles(localConfigFile, apfeConfigInJson, mModuleName, device);

        if (TestTarget.DEVICE.equals(mTarget)) {
            String deviceDest = String.format("%s%s.dynamic",
                    DynamicConfig.CONFIG_FOLDER_ON_DEVICE, mModuleName);
            if (!device.pushFile(hostFile, deviceDest)) {
                throw new TargetSetupError(String.format(
                        "Failed to push local '%s' to remote '%s'", hostFile.getAbsolutePath(),
                        deviceDest), device.getDeviceDescriptor());
            }
            mDeviceFilePushed = deviceDest;
        }
        // add host file to build
        buildHelper.addDynamicConfigFile(mModuleName, hostFile);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void tearDown(ITestDevice device, IBuildInfo buildInfo, Throwable e)
            throws DeviceNotAvailableException {
        // Remove any file we have pushed to the device, host file will be moved to the result
        // directory by ResultReporter upon invocation completion.
        if (mDeviceFilePushed != null && !(e instanceof DeviceNotAvailableException) && mCleanup) {
            device.executeShellCommand("rm -r " + mDeviceFilePushed);
        }
    }

    /**
     * Return the the first element of test-suite-tag from configuration if it's not empty,
     * otherwise, return the name from test-suite-info.properties.
     */
    @VisibleForTesting
    String getSuiteName() {
        List<String> testSuiteTags = mConfiguration.getConfigurationDescription().getSuiteTags();
        String suiteName = null;
        if (!testSuiteTags.isEmpty()) {
            if (testSuiteTags.size() >= 2) {
                CLog.i("More than 2 test-suite-tag are defined. test-suite-tag: " + testSuiteTags);
            }
            suiteName = testSuiteTags.get(0).toUpperCase();
        } else {
            suiteName = TestSuiteInfo.getInstance().getName();
        }
        CLog.i("Replace {suite-name} placeholder with " + suiteName + " in dynamic config url.");
        return suiteName;
    }

    @VisibleForTesting
    final File getLocalConfigFile(CompatibilityBuildHelper buildHelper, ITestDevice device)
            throws TargetSetupError {
        File localConfigFile = null;
        if (mExtractFromResource) {
            String lookupName = (mResourceFileName != null) ? mResourceFileName : mModuleName;
            InputStream dynamicFileRes = getClass().getResourceAsStream(
                    String.format("/%s.dynamic", lookupName));
            try {
                localConfigFile = FileUtil.createTempFile(lookupName, ".dynamic");
                FileUtil.writeToFile(dynamicFileRes, localConfigFile);
            } catch (IOException e) {
                FileUtil.deleteFile(localConfigFile);
                throw new TargetSetupError(
                        String.format("Fail to unpack '%s.dynamic' from resources", lookupName),
                        e, device.getDeviceDescriptor());
            }
            return localConfigFile;
        }

        // If not from resources look at local path.
        try {
            String lookupName = (mDynamicConfigName != null) ? mDynamicConfigName : mModuleName;
            localConfigFile = buildHelper.getTestFile(String.format("%s.dynamic", lookupName));
        } catch (FileNotFoundException e) {
            throw new TargetSetupError("Cannot get local dynamic config file from test directory",
                    e, device.getDeviceDescriptor());
        }
        return localConfigFile;
    }

    @VisibleForTesting
    File mergeConfigFiles(File localConfigFile, String apfeConfigInJson, String moduleName,
            ITestDevice device) throws TargetSetupError {
        File hostFile = null;
        try {
            hostFile = DynamicConfigHandler.getMergedDynamicConfigFile(
                    localConfigFile, apfeConfigInJson, moduleName);
            return hostFile;
        } catch (IOException | XmlPullParserException | JSONException e) {
            throw new TargetSetupError("Cannot get merged dynamic config file", e,
                    device.getDeviceDescriptor());
        } finally {
            if (mExtractFromResource) {
                FileUtil.deleteFile(localConfigFile);
            }
        }
    }
}
