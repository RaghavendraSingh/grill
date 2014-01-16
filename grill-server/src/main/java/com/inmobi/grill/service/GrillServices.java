package com.inmobi.grill.service;

import java.lang.reflect.Constructor;
import java.util.LinkedHashMap;
import java.util.Map;

import javax.ws.rs.WebApplicationException;

import com.inmobi.grill.query.service.EventServiceImpl;
import com.inmobi.grill.server.api.events.GrillEventService;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.hive.conf.HiveConf;
import org.apache.hive.service.CompositeService;
import org.apache.hive.service.Service;
import org.apache.hive.service.cli.CLIService;

import com.inmobi.grill.api.GrillConfConstants;
import com.inmobi.grill.service.session.GrillSessionImpl;

public class GrillServices extends CompositeService {
  public static final Log LOG = LogFactory.getLog(GrillServices.class);

  static {
    Configuration.addDefaultResource("grill-default.xml");
    Configuration.addDefaultResource("grill-site.xml");
  }

  public static final String GRILL_SERVICES_NAME = "grill_services";
  private static final GrillServices INSTANCE = new GrillServices(GRILL_SERVICES_NAME);
  private HiveConf conf;
  private boolean inited = false;
  private CLIService cliService;
  private final Map<String, Service> services = new LinkedHashMap<String, Service>();

  public GrillServices(String name) {
    super(name);
  }

  @Override
  public synchronized void init(HiveConf hiveConf) {
    if (getServiceState() == STATE.INITED) {
      LOG.info("GrillServices already initialized");
      return;
    }

    conf = hiveConf;
    conf.setVar(HiveConf.ConfVars.HIVE_SESSION_IMPL_CLASSNAME, GrillSessionImpl.class.getCanonicalName());
    cliService = new CLIService();

    // Add default services
    addService(cliService);
    addService(new EventServiceImpl(GrillEventService.NAME));

    // Add configured services, these are instances of GrillService which need a CLIService instance
    // for session management
    String[] serviceNames = conf.getStrings(GrillConfConstants.GRILL_SERVICE_NAMES);
    for (String sName : serviceNames) {
      try {
        Class<? extends GrillService> serviceClass = conf.getClass(
          GrillConfConstants.getServiceImplConfKey(sName), null,
          GrillService.class);
        LOG.info("Adding " + sName + " service with " + serviceClass);
        Constructor<?> constructor = serviceClass.getConstructor(CLIService.class);
        GrillService service  = (GrillService) constructor.newInstance(new Object[]
          {cliService});
        addService(service);
      } catch (Exception e) {
        LOG.warn("Could not add service:" + sName, e);
        throw new WebApplicationException(e);
      }
    }

    for (Service svc : getServices()) {
      services.put(svc.getName(), svc);
    }

    // This will start all services in the order they were added
    super.init(conf);
    LOG.info("Initialized grill services: " + services.keySet().toString());
  }

  public static GrillServices get() {
    return INSTANCE;
  }

  @SuppressWarnings("unchecked")
  public <T extends Service> T getService(String sName) {
    return (T) services.get(sName);
  }
}