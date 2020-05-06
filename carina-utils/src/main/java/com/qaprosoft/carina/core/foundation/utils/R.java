/*******************************************************************************
 * Copyright 2013-2020 QaProSoft (http://www.qaprosoft.com).
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *******************************************************************************/
package com.qaprosoft.carina.core.foundation.utils;

import java.net.URL;
import java.util.HashMap;
import java.util.Map;
import java.util.Properties;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.qaprosoft.carina.core.foundation.crypto.CryptoTool;
import org.apache.commons.lang3.StringUtils;
import org.apache.log4j.Logger;

import com.qaprosoft.carina.core.foundation.commons.SpecialKeywords;
import com.qaprosoft.carina.core.foundation.exception.InvalidConfigurationException;

/**
 * R - loads properties from resource files.
 *
 * @author Aliaksei_Khursevich
 *         <a href="mailto:hursevich@gmail.com">Aliaksei_Khursevich</a>
 *
 */
public enum R {
    API("api.properties"),

    CONFIG("config.properties"),

    TESTDATA("testdata.properties"),

    EMAIL("email.properties"),

    REPORT("report.properties"),

    DATABASE("database.properties"),

    ZAFIRA("zafira.properties");

    private static final Logger LOGGER = Logger.getLogger(R.class);

    private static final String OVERRIDE_SIGN = "_";

    private static Pattern CRYPTO_PATTERN = Pattern.compile(SpecialKeywords.CRYPT);

    private String resourceFile;

    // temporary thread/test properties which is cleaned on afterTest phase for current thread. It can override any value from below R enum maps
    private static ThreadLocal<Properties> testProperties = new ThreadLocal<>();

    // permanent global configuration map 
    private static Map<String, Properties> propertiesHolder = new HashMap<>();
    
    // init global configuration map statically
    static {
        for (R resource : values()) {
            try {
                Properties properties = new Properties();

                URL baseResource = ClassLoader.getSystemResource(resource.resourceFile);
                if (baseResource != null) {
                    properties.load(baseResource.openStream());
                    LOGGER.debug("Base properties loaded: " + resource.resourceFile);
                }

                URL overrideResource;
                String resourceName = OVERRIDE_SIGN + resource.resourceFile;
                while ((overrideResource = ClassLoader.getSystemResource(resourceName)) != null) {
                    properties.load(overrideResource.openStream());
                    LOGGER.debug("Override properties loaded: " + resourceName);
                    resourceName = OVERRIDE_SIGN + resourceName;
                }

                // Overrides properties by systems values
                for (Object key : properties.keySet()) {
                    String systemValue = System.getProperty((String) key);
                    if (!StringUtils.isEmpty(systemValue)) {
                        properties.put(key, systemValue);
                    }
                }

                if (resource.resourceFile.contains("config.properties")) {
                    // TODO: investigate if we needed env variables analysis using System.getenv() as well
                    final String prefix = SpecialKeywords.CAPABILITIES + ".";
                    // read all java arguments and redefine capabilities.* items
                    @SuppressWarnings({ "unchecked", "rawtypes" })
                    Map<String, String> javaProperties = new HashMap(System.getProperties());
                    for (Map.Entry<String, String> entry : javaProperties.entrySet()) {
                        String key = entry.getKey();
                        if (key.toLowerCase().startsWith(prefix)) {
                            String value = entry.getValue();
                            if (!StringUtils.isEmpty(value)) {
                                properties.put(key, value);
                            }
                        }
                    }
                }
                propertiesHolder.put(resource.resourceFile, properties);
            } catch (Exception e) {
                throw new InvalidConfigurationException("Invalid config in '" + resource + "': " + e.getMessage());
            }
        }
    }
    
    R(String resourceKey) {
        this.resourceFile = resourceKey;
    }

    /**
     * Put and update globally value for properties context.
     * 
     * @param key String
     * @param value String
     */
    public void put(String key, String value) {
        put(key, value, false);
    }

    /**
     * Put and update globally or for current test only value for properties context.
     * 
     * @param key String
     * @param value String
     * @param currentTestOnly boolean
     */
    public void put(String key, String value, boolean currentTestOnly) {
        if (currentTestOnly) {
            //declare temporary property key
            LOGGER.warn("Override property for current test '" + key + "=" + value + "'!");
            getTestProperties().put(key, value);
        } else {
            // override globally configuration map property 
            propertiesHolder.get(resourceFile).put(key, value);
        }
    }
    
    /**
     * Verify if key is declared in data map.
     * 
     * @return boolean
     */
    public boolean containsKey(String key) {
        return propertiesHolder.get(resourceFile).containsKey(key) || getTestProperties().containsKey(key);
    }

    /**
     * Returns value either from systems properties or config properties context.
     * Systems properties have higher priority.
     * Decryption is performed if required.
     * 
     * @param key Requested key
     * @return config value
     */
    public String get(String key) {
        String value = getTestProperties().getProperty(key);
        if (value != null) {
            LOGGER.warn("Overridden '" + key + "=" + value + "' property will be used for current test!");
            return value;
        }
        
        value = CONFIG.resourceFile.equals(resourceFile) ? PlaceholderResolver.resolve(propertiesHolder.get(resourceFile), key)
                : propertiesHolder.get(resourceFile).getProperty(key);


        if(isEncrypted(value, CRYPTO_PATTERN)) {
            value = decrypt(value, CRYPTO_PATTERN);;
        }

        // TODO: why we return empty instead of null?
        // [VD] as designed empty MUST be returned
        return value != null ? value : StringUtils.EMPTY;
    }


    public int getInt(String key) {
        return Integer.parseInt(get(key));
    }

    public long getLong(String key) {
        return Long.parseLong(get(key));
    }

    public double getDouble(String key) {
        return Double.parseDouble(get(key));
    }

    public boolean getBoolean(String key) {
        return Boolean.valueOf(get(key));
    }

    public static String getResourcePath(String resource) {
        String path = StringUtils.removeStart(ClassLoader.getSystemResource(resource).getPath(), "/");
        path = StringUtils.replaceChars(path, "/", "\\");
        path = StringUtils.replaceChars(path, "!", "");
        return path;
    }

	public Properties getProperties() {
		Properties globalProp = propertiesHolder.get(resourceFile);
		// Glodal properties will be updated with test specific properties
		if (!testProperties.get().isEmpty()) {
			Properties testProp = testProperties.get();
			LOGGER.debug(String.format("CurrentTestOnly properties has [%s] entries.", testProp.size()));
			LOGGER.debug(testProp.toString());
			@SuppressWarnings({ "unchecked", "rawtypes" })
			Map<String, String> testCapabilitiesMap = new HashMap(testProp);
			testCapabilitiesMap.keySet().stream().forEach(i -> {
				if (globalProp.containsKey(i)) {
					LOGGER.debug(String.format(
							"Global properties already contains key --- %s --- with value --- %s ---. Global property will be overridden by  --- %s --- from test properties.",
							i, globalProp.get(i), testProp.get(i)));
				} else {
					LOGGER.debug(String.format(
							"Global properties isn't contains key --- %s ---.  Global key --- %s --- will be set to --- %s ---  from test properties.",
							i, i, testProp.get(i)));
				}
				globalProp.setProperty(i, (String) testProp.get(i));
			});
		}
		return globalProp;
	}
    
    public void clearTestProperties() {
        LOGGER.debug("Clear temporary test properties.");
        testProperties.remove();
    }
    
    public Properties getTestProperties() {
        if (testProperties.get() == null) {
            // init temporary properties at first call
            Properties properties = new Properties();
            testProperties.set(properties);
        }
        
        return testProperties.get();
    }

    private boolean isEncrypted(String content, Pattern pattern) {
        String wildcard = pattern.pattern().substring(pattern.pattern().indexOf("{") + 1,
                pattern.pattern().indexOf(":"));
        if (content != null && content.contains(wildcard)) {
            Matcher matcher = pattern.matcher(content);
            if (matcher.find()) {
                LOGGER.debug("'" + content + "' require decryption.");
                return true;
            }
        }
        return false;
    }

    private String decrypt(String content, Pattern pattern) {
        try {
            CryptoTool cryptoTool = new CryptoTool(Configuration.get(Configuration.Parameter.CRYPTO_KEY_PATH));
            return cryptoTool.decryptByPattern(content, pattern);
        } catch (Exception e) {
            LOGGER.error("Error during decrypting '" + content + "'. Please check error: ", e);
            return content;
        }
    }

}
