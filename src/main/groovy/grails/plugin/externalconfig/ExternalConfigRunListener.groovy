package grails.plugin.externalconfig

import grails.util.Environment
import groovy.transform.CompileStatic
import org.grails.config.NavigableMapPropertySource
import org.grails.config.PropertySourcesConfig
import org.grails.config.yaml.YamlPropertySourceLoader
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import org.springframework.boot.SpringApplication
import org.springframework.boot.SpringApplicationRunListener
import org.springframework.context.ConfigurableApplicationContext
import org.springframework.core.env.ConfigurableEnvironment
import org.springframework.core.env.MapPropertySource
import org.springframework.core.io.DefaultResourceLoader
import org.springframework.core.io.Resource
import org.springframework.core.io.ResourceLoader

@CompileStatic
class ExternalConfigRunListener implements SpringApplicationRunListener {

	private ResourceLoader defaultResourceLoader = new DefaultResourceLoader()
	private YamlPropertySourceLoader yamlPropertySourceLoader = new YamlPropertySourceLoader()
	private Logger log = LoggerFactory.getLogger('grails.plugin.externalconfig.ExternalConfig')

    ExternalConfigRunListener(SpringApplication application, String[] args) { }
	
	@Override
	void environmentPrepared(ConfigurableEnvironment environment) {
		List locations = environment.getProperty('grails.config.locations', List, [])
		String encoding = environment.getProperty('grails.config.encoding', String, 'UTF-8')
		
		for (location in locations) {
			MapPropertySource propertySource = null
			if (location instanceof Class) {
				propertySource = loadClassConfig(location as Class)
			} else {
				String finalLocation = location.toString()
				// Replace ~ with value from system property 'user.home' if set
				String userHome = System.properties.getProperty('user.home')
				if (userHome && finalLocation.startsWith('~/')) {
					finalLocation = "file:${userHome}${finalLocation[1..-1]}"
				}
				finalLocation = environment.resolvePlaceholders(finalLocation) 
				
				Resource resource = defaultResourceLoader.getResource(finalLocation)
				if (resource.exists()) {
                    Map currentProperties = getCurrentConfig(environment)
					if (finalLocation.endsWith('.groovy')) {
						propertySource = loadGroovyConfig(resource, encoding, currentProperties)
					} else if (finalLocation.endsWith('.yml')) {
						environment.activeProfiles
						propertySource = loadYamlConfig(resource)
					} else {
						// Attempt to load the config as plain old properties file (POPF)
						propertySource = loadPropertiesConfig(resource)
					}
				} else {
					log.debug("Config file {} not found", [finalLocation] as Object[])
				}
			}
			if (propertySource?.getSource() && !propertySource.getSource().isEmpty()) {
				environment.propertySources.addFirst(propertySource)
			}
		}
	}

	private MapPropertySource loadClassConfig(Class location) {
		log.info("Loading config class {}", location.name)
		Map properties = new ConfigSlurper(Environment.current.name).parse((Class) location)?.flatten()
		new MapPropertySource(location.toString(), properties)
	}

	private MapPropertySource loadGroovyConfig(Resource resource, String encoding, Map currentConfig) {
		log.info("Loading groovy config file {}", resource.URI)
		String configText = resource.inputStream.getText(encoding)
		ConfigSlurper slurper = new ConfigSlurper(Environment.current.name)
		slurper.binding = currentConfig
        ConfigObject configObject = slurper.parse(configText)
        Map properties = configText ? configObject?.flatten() : [:]
		new MapPropertySource(resource.filename, properties)
	}

	private NavigableMapPropertySource loadYamlConfig(Resource resource) {
		log.info("Loading YAML config file {}", resource.URI)
		NavigableMapPropertySource propertySource = yamlPropertySourceLoader.load(resource.filename, resource, null) as NavigableMapPropertySource
		return propertySource
	}

	private MapPropertySource loadPropertiesConfig(Resource resource) {
		log.info("Loading properties config file {}", resource.URI)
		Properties properties = new Properties()
		properties.load(resource.inputStream)
		new MapPropertySource(resource.filename, properties as Map)
	}

	// Spring Boot 1.4 or higher
	void starting() { }
	// Spring Boot 1.3
	void started() { }
	void contextPrepared(ConfigurableApplicationContext context) { }
	void contextLoaded(ConfigurableApplicationContext context) { }
	void finished(ConfigurableApplicationContext context, Throwable exception) { }

    static Map getCurrentConfig(ConfigurableEnvironment environment) {
        return new PropertySourcesConfig(environment.propertySources)
    }
}
