package concord4pi;

import org.tinylog.Level;

import concord4pi.config.*;
import concord4pi.logging.*;

public class concord4pi {

	private MainController masterControl;
	
	public static void main(final String[] args) {
		final concord4pi program = new concord4pi();
		program.go();
	}

	public concord4pi() {
		Config appConfiguration;
		LogEngine logger;
		
		//Get the application config & verify
		appConfiguration = new Config();
		if(!appConfiguration.valid()) {
			System.out.println("The config file could not be found or is not configured correctly.");
			System.out.println("Please fix your config file (./concord4pi.config) and retry.");
			//the logger isn't set up yet, and this is when the values are most worth seeing
			System.out.println("Read from " + appConfiguration.getConfigFileName() + ":");
			for(String setting : appConfiguration.describe()) {
				System.out.println("  " + setting);
			}
			System.exit(0);
		}
		else {
			//Set up the Logger
			logger = new LogEngine(appConfiguration);
			logger.log("Configuration from " + appConfiguration.getConfigFileName() + ":", Level.INFO);
			for(String setting : appConfiguration.describe()) {
				logger.log("  " + setting, Level.INFO);
			}
			masterControl = new MainController(appConfiguration, logger);
		}
	}
	
	public void go() {
		masterControl.start();
		masterControl.shutdown();
	}
	
	
}
