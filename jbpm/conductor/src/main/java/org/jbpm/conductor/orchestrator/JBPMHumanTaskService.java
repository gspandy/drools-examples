package org.jbpm.conductor.orchestrator;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.io.Serializable;
import java.net.URL;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.Map;
import java.util.Properties;

import javax.naming.InitialContext;
import javax.naming.NamingException;
import javax.persistence.EntityManagerFactory;
import javax.persistence.Persistence;

import org.drools.SystemEventListenerFactory;
import org.jbpm.conductor.util.DataSourceHelper;
import org.jbpm.task.Group;
import org.jbpm.task.User;
import org.jbpm.task.UserInfo;
import org.jbpm.task.service.DefaultEscalatedDeadlineHandler;
import org.jbpm.task.service.EscalatedDeadlineHandler;
import org.jbpm.task.service.TaskServer;
import org.jbpm.task.service.TaskService;
import org.jbpm.task.service.UserGroupCallback;
import org.jbpm.task.service.UserGroupCallbackManager;
import org.jbpm.task.service.hornetq.HornetQTaskServer;
import org.jbpm.task.service.jms.JMSTaskServer;
import org.jbpm.task.service.jms.TaskServiceConstants;
import org.jbpm.task.service.mina.MinaTaskServer;

/**
 * 
 * Responsible for configuring entire task server based in jBPM.properties parameters.
 * 
 * There are two sections of the configuration:
 * <ul>
 * 	<li>transport related - to configure transport of choice (hornetq, jms,mina)</li>
 * 	<li>general - configures internal components of task server (escalation, user group callback)</li>
 * </ul>
 * 
 * Main parameter that controls what trasport will be configured is <code>active.config</code>. It has three acceptable values:
 * <ul>
 * 	<li>hornetq</li>
 * 	<li>jms</li>
 * 	<li>mina</li>
 * </ul>
 * be default it uses hornetq as transport.
 * 
 * Dedicated parameters for transport configuration:
 * <b>HornetQ</b>
 * <ul>
 *  <li>hornetq.host</li>
 * 	<li>hornetq.port</li>
 * </ul>
 * 
 * <b>JMS</b>
 * <ul>
 * 	<li>JMSTaskServer.connectionFactory</li>
 * 	<li>JMSTaskServer.transacted</li>
 * 	<li>JMSTaskServer.acknowledgeMode</li>
 * 	<li>JMSTaskServer.queueName</li>
 * 	<li>JMSTaskServer.responseQueueName</li>
 * </ul>
 * 
 * <b>Mina</b>
 * <ul>
 *  <li>mina.host</li>
 * 	<li>mina.port</li>
 * </ul>
 * 
 * @author kylin
 *
 */
public class JBPMHumanTaskService implements Serializable {

	private static final long serialVersionUID = 2039423044016820388L;
	
	private Properties props;
	
	/*
	 * jBPM.properties should be loaded before HumanTaskService initialized
	 */
	public JBPMHumanTaskService(Properties props) {
		this.props = props;
	}
	
	public JBPMHumanTaskService(Properties props, boolean initDataSource) {
		
		this(props);
		
		if(initDataSource) {
			DataSourceHelper.setupDataSource(props);
		} 	
	}

	private TaskServer server = null;
	
    private Thread thread = null;
    
    public void init() {
    	
    	EntityManagerFactory emf = Persistence.createEntityManagerFactory(getConfigParameter("task.persistence.unit", "org.jbpm.task"));
    	
    	String escalationHandlerClass = getConfigParameter("escalated.deadline.handler.class", DefaultEscalatedDeadlineHandler.class.getName());
    	
    	TaskService taskService = null;
    	try {
        	EscalatedDeadlineHandler handler = getInstance(escalationHandlerClass);
        	if (handler instanceof DefaultEscalatedDeadlineHandler) {
        		UserInfo userInfo = null;
        		try {
	        		String userInfoClass = getConfigParameter("user.info.class", null);
		        	userInfo = getInstance(userInfoClass);
        		} catch (IllegalArgumentException e) {
//        			Properties registryProps = new Properties();
//        			registryProps.load(this.getClass().getResourceAsStream("/userinfo.properties"));
//					userInfo = new DefaultUserInfo(registryProps);
				}
	        	
	        	((DefaultEscalatedDeadlineHandler)handler).setUserInfo(userInfo);
        	}
        	
        	taskService = new TaskService(emf, SystemEventListenerFactory.getSystemEventListener(), handler);
        } catch (Exception e) {
        	taskService = new TaskService(emf, SystemEventListenerFactory.getSystemEventListener());
		}
    	
    	String usersConfig = getConfigParameter("load.users", "");
        String groupsConfig = getConfigParameter("load.groups", "");
        
        Map<String, User> users = new HashMap<String, User>();
        Map<String, Group> groups = new HashMap<String, Group>();
        
        try {
            if (usersConfig != null && usersConfig.length() > 0) {
                if (usersConfig.endsWith(".mvel")) {
                   
                    Map vars = new HashMap();
                    Reader reader = new InputStreamReader(getConfigFileStream(usersConfig) );     
                    users = ( Map<String, User> ) TaskService.eval( reader, vars );   
                } else if (usersConfig.endsWith(".properties"))  {
                    Properties props = new Properties();
                    props.load(getConfigFileStream(usersConfig));
                    
                    Enumeration<?> ids = props.propertyNames();
                    while(ids.hasMoreElements()) { 
                        Object idObject = ids.nextElement();
                        if( idObject instanceof String ) {
                            String id = (String) idObject;
                            users.put(id, new User(id));
                        }
                    }
                }
            }
    	} catch (Exception e) {
            System.err.println("Problem loading users from specified file: " + usersConfig + " error message: " + e);
        }
        
        try {
            if (groupsConfig != null && groupsConfig.length() > 0) {
                if (groupsConfig.endsWith(".mvel")) {
                    Map vars = new HashMap();
                    Reader reader = new InputStreamReader( getConfigFileStream(groupsConfig) );      
                    groups = ( Map<String, Group> ) TaskService.eval( reader, vars );     
                } else if (groupsConfig.endsWith(".properties"))  {
                    Properties props = new Properties();
                    props.load(getConfigFileStream(groupsConfig));
                    
                    Enumeration<?> ids = props.propertyNames();
                    while(ids.hasMoreElements()) { 
                        Object idObject = ids.nextElement();
                        if( idObject instanceof String ) {
                            String id = (String) idObject;
                            groups.put(id, new Group(id));
                        }
                    }
                }
            }
        } catch (Exception e) {
            System.err.println("Problem loading groups from specified file: " + groupsConfig + " error message: " + e);
        }
        
        System.out.println("Human Task Service Users: " + users);
        System.out.println("Human Task Service Groups: " + groups);
        
        taskService.addUsersAndGroups(users, groups);
        
        String activeConfig = getConfigParameter("active.config", "hornetq");
        if ("mina".equalsIgnoreCase(activeConfig)) {
	        int port = Integer.parseInt(getConfigParameter("mina.port", "9123"));
        	String host = getConfigParameter("mina.host", "localhost");
        	// start server
	        server = new MinaTaskServer(taskService, port, host);
	        thread = new Thread(server);
	        thread.start();
	        System.out.println("Apache Mina Task service started correctly !");
	        System.out.println("Apache Mina Task service running (host " + host + " port " + port + ") ...");
	        
		} else if ("hornetq".equalsIgnoreCase(activeConfig)) {
        	int port = Integer.parseInt(getConfigParameter("hornetq.port", "5153"));
        	String host = getConfigParameter("hornetq.host", "localhost");
        	
        	server = new HornetQTaskServer(taskService, host, port);
    		thread = new Thread(server);
    		thread.start();
    		System.out.println("HornetQ Task service started correctly !");
	        System.out.println("HornetQ Task service running (host " + host + " port " + port + ") ...");
	        
        } else if ("jms".equalsIgnoreCase(activeConfig)) {
        	Properties connProperties = new Properties();
        	connProperties.setProperty(TaskServiceConstants.TASK_SERVER_CONNECTION_FACTORY_NAME, getConfigParameter(TaskServiceConstants.TASK_SERVER_CONNECTION_FACTORY_NAME, null));
        	connProperties.setProperty(TaskServiceConstants.TASK_SERVER_TRANSACTED_NAME, getConfigParameter(TaskServiceConstants.TASK_SERVER_TRANSACTED_NAME, null));
        	connProperties.setProperty(TaskServiceConstants.TASK_SERVER_ACKNOWLEDGE_MODE_NAME, getConfigParameter(TaskServiceConstants.TASK_SERVER_ACKNOWLEDGE_MODE_NAME, ""));
        	connProperties.setProperty(TaskServiceConstants.TASK_SERVER_QUEUE_NAME_NAME, getConfigParameter(TaskServiceConstants.TASK_SERVER_QUEUE_NAME_NAME, null));
        	connProperties.setProperty(TaskServiceConstants.TASK_SERVER_RESPONSE_QUEUE_NAME_NAME, getConfigParameter(TaskServiceConstants.TASK_SERVER_RESPONSE_QUEUE_NAME_NAME, null));
        	try {
	        	server = new JMSTaskServer(taskService, connProperties, new InitialContext());
	        	thread = new Thread(server);
	    		thread.start();
	    		System.out.println("JMS Task service started correctly !");
		        System.out.println("JMS Task service running ...");
			} catch (NamingException e) {
				throw new RuntimeException("Error while starting JMS Task Service", e);
			}
        }
        
        UserGroupCallbackManager manager = UserGroupCallbackManager.getInstance();
        if (!manager.existsCallback()) {
            String callbackClass = getConfigParameter("user.group.callback.class", "");
            
            
        	UserGroupCallback userGroupCallback = getInstance(callbackClass);
        		
        	manager.setCallback(userGroupCallback);
        }
        System.out.println("Task service startup completed successfully !");
    	
    }
    
    public void destroy() {

		try {
			this.server.stop();
		} catch (Exception e) {
			System.out.println("Exception while stopping task server " + e.getMessage());
		}
		try {
			 this.thread.interrupt();
		} catch (Exception e) {
			System.out.println("Exception while stopping task server thread " + e.getMessage());
		}
	}
    
    public TaskServer getServer() {
		return server;
	}
    
    protected String getConfigParameter(String name, String defaultValue) {
    	
    	String paramValue = props.getProperty(name);
    	
    	if (paramValue != null && paramValue.length() > 0) {
    		return paramValue;
    	}
    	
    	if (defaultValue == null) {
    		throw new IllegalArgumentException("Missing configuration property name: " + name);
    	}
    	
    	return defaultValue;
    }
    
    protected <T> T getInstance(String className) {
    	
		if (className == null || "".equalsIgnoreCase(className)) {
			return null;
		}
        
		Object instance;
		try {
			instance = Class.forName(className).newInstance();
		
			return (T) instance;
		} catch (Exception e) {
			throw new IllegalArgumentException("Error while creating instance of configurable class, class name: " + className, e);
		}
    }
    
    protected InputStream getConfigFileStream(String location) throws IOException {
    	
        if (location == null) {
            return null;
        }
        
        URL configLocation = null;

		if (location.startsWith("classpath:")) {
            String pathOnly = location.replaceFirst("classpath:", "");
            configLocation = JBPMHumanTaskService.class.getResource(pathOnly);
        } else {
            configLocation = new URL(location);
        }
		
		if (configLocation == null) {
			throw new IllegalArgumentException("File was not found at given location " + location);
		}
		
        return configLocation.openStream();
    }

}