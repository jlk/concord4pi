package concord4pi.config;

import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import java.util.TreeSet;

public class Config {
	
	private static Properties prop;
	private static final String defaultConfigFileName = "config/concord4pi.config";
	private final String configFileName;
	//the same as Paho's default connect timeout
	public static final int DEFAULT_MQTT_TIMEOUT_SECONDS = 30;

	public Config() {
		this(defaultConfigFileName);
	}

	public Config(String configFileName) {
		this.configFileName = configFileName;
		prop = new Properties();
		setupConfigReader();
	}
	
	private void setupConfigReader() {
		InputStream configFileStream = null;
		try {
			configFileStream = new FileInputStream(configFileName);
			prop.load(configFileStream);
			configFileStream.close();
		} catch (IOException e) {
			e.printStackTrace();
			
		} finally {
			if(configFileStream != null) {
				try {
					configFileStream.close();
				}
				catch(IOException e) {
					e.printStackTrace();
				}
			}
		}
	}
	
	public String getSerialDeviceName() {
		return getProperty("SerialDevice");
	}
	
	//how long a blocking MQTT call (connect, publish, subscribe) waits for the broker.  Missing
	//means the default; valid() rejects anything that isn't a positive whole number of seconds
	public int getMQTTTimeoutSeconds() {
		String value = getProperty("MQTTTimeoutSeconds");
		if(value == null) {
			return DEFAULT_MQTT_TIMEOUT_SECONDS;
		}
		return Integer.parseInt(value.trim());
	}

	public String getLogFilename() {
		return getProperty("Log_Filename");
	}
	
	public int getDefaultLogLevel() {
		return getPropertyInt("Log_DefaultLevel");
	}
	
	public int getPropertyInt(String key) {
		String value = prop.getProperty(key);
		if (value == null) {
			throw new IllegalArgumentException("Property '" + key + "' not found in config file");
		}
		return Integer.parseInt(value);
	}
	
	public String getProperty(String key) {
		return prop.getProperty(key);
	}
	
	public String getConfigFileName() {
		return configFileName;
	}
	
	//every key=value read from the file, sorted by key, safe to log: a key with "password" in its
	//name shows <hidden> (or <empty>), and so does the password part of a URL like tcp://user:pass@host
	public List<String> describe() {
		List<String> lines = new ArrayList<String>();
		for(String key : new TreeSet<String>(prop.stringPropertyNames())) {
			String value = prop.getProperty(key);
			if(key.toLowerCase().contains("password")) {
				value = value.isEmpty() ? "<empty>" : "<hidden>";
			}
			else {
				value = value.replaceAll("(://[^/@:]*):[^/@]*@", "$1:<hidden>@");
			}
			lines.add(key + "=" + value);
		}
		return lines;
	}
	
	//true only for exactly "true"; a missing key is false rather than null
	public boolean getPropertyBoolean(String key) {
		return "true".equals(prop.getProperty(key));
	}
	
	public boolean valid() {
		boolean isValid = true;
		
		//validity checks here
		String serialDevice = getSerialDeviceName();
		if(serialDevice == null || serialDevice.isEmpty()) { isValid = false; }

		String mqttTimeout = getProperty("MQTTTimeoutSeconds");
		//a positive whole number, short enough to be an int
		if(mqttTimeout != null && !mqttTimeout.trim().matches("0*[1-9][0-9]{0,8}")) { isValid = false; }

		return isValid;
	}
}
