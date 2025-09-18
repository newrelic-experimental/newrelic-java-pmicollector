package com.newrelic.instrumentation.labs.was.pmi;

import java.security.PrivilegedActionException;
import java.security.PrivilegedExceptionAction;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;

import javax.management.MBeanServer;
import javax.management.ObjectInstance;
import javax.management.ObjectName;

import com.ibm.websphere.management.AdminClient;
import com.ibm.websphere.management.AdminService;
import com.ibm.websphere.management.AdminServiceFactory;
import com.ibm.websphere.management.exception.AdminException;
import com.ibm.websphere.pmi.stat.StatDescriptor;
import com.ibm.websphere.pmi.stat.WSStats;
import com.ibm.ws.pmi.stat.AverageStatisticImpl;
import com.ibm.ws.pmi.stat.BoundaryStatisticImpl;
import com.ibm.ws.pmi.stat.CountStatisticImpl;
import com.ibm.ws.pmi.stat.DoubleStatisticImpl;
import com.ibm.ws.pmi.stat.RangeStatisticImpl;
import com.ibm.ws.pmi.stat.StatisticImpl;
import com.ibm.ws.pmi.stat.StatsImpl;
import com.ibm.ws.security.core.SecurityContext;
import com.newrelic.agent.config.AgentConfig;
import com.newrelic.agent.config.AgentConfigListener;
import com.newrelic.agent.service.ServiceFactory;
import com.newrelic.api.agent.Config;
import com.newrelic.api.agent.Logger;
import com.newrelic.api.agent.NewRelic;

public class PMISampler implements Runnable,AgentConfigListener {

	private static PMISampler instance = null;
	private boolean collect = true;
	private boolean sendToEvents = true;
	private boolean sendToMetrics = false;
	private static final String ENABLED = "PMI.enabled";
	private static final String EVENTS_KEY = "PMI.events_enabled";
	private static final String METRICS_ENABLED = "PMI.metrics_enabled";
	private static final String STAT_TYPE_FILTER_LIST = "PMI.stat_type_filter";
	private static final String NAME_FILTER_LIST = "PMI.name_filter";
	private static final String DEBUG_MODE = "PMI.debug";
	private static final String MANAGED = "ManagedProcess";
	private List<String> stattype_filters = null;
	private List<String> name_filters = null;
	private AdminClient ac = null;
	private AdminService as = null;
	private boolean initialized = false;
	private int failed = 0;
	private boolean debug = false;

	public static void StartInstance() {
		if(instance == null) {
			instance = new PMISampler();
			ScheduledExecutorService executorService = Executors.newScheduledThreadPool(3);
			executorService.scheduleAtFixedRate(instance, 2, 1, TimeUnit.MINUTES);
			ServiceFactory.getConfigService().addIAgentConfigListener(instance);
		}
	}

	Collection<MBeanServer> serverList;

	private PMISampler() {
		serverList = new ArrayList<>();
		Config config = NewRelic.getAgent().getConfig();
		processConfig(config);
	}

	private ObjectName[] queryMBean()  throws Exception {

		StringBuilder sb = new StringBuilder();

		sb.append("type=");
		sb.append("Server");

		sb.append(sb.length()>0 ? ",":"");
		sb.append("*");

		ObjectName on = new ObjectName("WebSphere:"+ sb);

		@SuppressWarnings("rawtypes")
		Set set = null;

		if(ac != null) {
			set = ac.queryMBeans(on, null);
		} else if(as != null) {
			set = as.queryMBeans(on, null);
		}

		Object[] objArr = set != null ? set.toArray() : new Object[0];
		ObjectName[] arr = new ObjectName[objArr.length];

		for(int i=0;i<objArr.length;i++) {
			Object obj = objArr[i];
			if(obj instanceof ObjectInstance) {
				ObjectInstance objInst = (ObjectInstance)objArr[i];
				arr[i] = objInst.getObjectName();
			} else if(obj instanceof ObjectName) {
				arr[i] = (ObjectName)objArr[i];
			}
		}

		return arr;
	}



	private ObjectName[] queryServerMBean(String node,String process)  throws Exception {

		StringBuilder sb = new StringBuilder();

		sb.append("type=");
		sb.append("Perf");

		if(node != null) {
			sb.append(sb.length()>0 ? ",":"");
			sb.append("node=");
			sb.append(node);
		}
		if(process != null) {
			sb.append(sb.length()>0 ? ",":"");
			sb.append("process=");
			sb.append(process);
		}
		sb.append(sb.length()>0 ? ",":"");
		sb.append("*");

		ObjectName on = new ObjectName("WebSphere:"+ sb);

		@SuppressWarnings("rawtypes")
		Set set = null;

		if(ac != null) {
			set = ac.queryMBeans(on, null);
		} else if(as != null) {
			set = as.queryMBeans(on, null);
		}

		Object[] objArr = set != null ? set.toArray() : new Object[0];
		ObjectName[] arr = new ObjectName[objArr.length];

		for(int i=0;i<objArr.length;i++) {
			Object obj = objArr[i];
			if(obj instanceof ObjectInstance) {
				ObjectInstance objInst = (ObjectInstance)objArr[i];
				arr[i] = objInst.getObjectName();
			} else if(obj instanceof ObjectName) {
				arr[i] = (ObjectName)objArr[i];
			}
		}

		return arr;
	}


	private ArrayList<PMIMBeans> findPerfMBeans() throws Exception {
		ArrayList<PMIMBeans> mbeans = new ArrayList<>();
		ObjectName[] servers = queryMBean();
		NewRelic.getAgent().getLogger().log(Level.FINEST, "Retrieved {0} server MBeans", servers.length);

		for(ObjectName server : servers) {
			String process = server.getKeyProperty("process");
			String node = server.getKeyProperty("node");

			NewRelic.getAgent().getLogger().log(Level.FINEST, "ServerBean: {0}", server);

			ObjectName[] perfs = queryServerMBean(node, process);
			NewRelic.getAgent().getLogger().log(Level.FINEST, "Retrieved {0} perf MBeans", perfs.length);
			if(perfs.length == 0) {
				continue;
			}

			PMIMBeans pmiMBeans = new PMIMBeans(server, perfs[0]);
			mbeans.add(pmiMBeans);
		}

		return mbeans;

	}

	private void setAdminClient(AdminClient aClient) {
		ac = aClient;
	}

	private void setAdminService(AdminService aService) {
		as = aService;
	}

	@SuppressWarnings("rawtypes")
	private void init() throws AdminException {
		final HashMap<String,Object> eventMap = new HashMap<>();
		boolean securityEnabled = SecurityContext.isSecurityEnabled();
		eventMap.put("SecurityEnabled", securityEnabled);

		if(!securityEnabled) {
			AdminService aService = AdminServiceFactory.getAdminService();

			String procType = aService.getProcessType();
			eventMap.put("ProcType", procType);
			if(procType.equals(MANAGED)) {
				ac = aService.getDeploymentManagerAdminClient();
				as = null;
				eventMap.put("PMISource", "AdminClient");
			} else {
				as = aService;
				ac = null;
				eventMap.put("PMISource", "AdminService");
			}
		} else {
			PrivilegedExceptionAction action = () -> {
				AdminService aService = AdminServiceFactory.getAdminService();
				String procType = aService.getProcessType();
				eventMap.put("ProcType", procType);

				if(procType.equals(MANAGED)) {
					PMISampler.this.setAdminClient(aService.getDeploymentManagerAdminClient());
					PMISampler.this.setAdminService(null);
					eventMap.put("PMISource", "AdminClient");
				} else {
					PMISampler.this.setAdminClient(null);
					PMISampler.this.setAdminService(aService);
					eventMap.put("PMISource", "AdminService");
				}

				return null;
			};
			try {
				SecurityContext.runAsSystem(action);
			} catch(PrivilegedActionException e) {
				NewRelic.getAgent().getLogger().log(Level.FINE, e, "Exception while executing priviledged action");
			}
		}

		initialized = ac != null || as != null;
		eventMap.put("Initialized", initialized);
		NewRelic.getAgent().getInsights().recordCustomEvent("PMIInitialization", eventMap);
		String message;
		if(initialized) {
			if(ac != null) {
				message = "Using AdminClient: "+ac;
			} else {
				message = "Using AdminService: "+as;
			}
		} else {
			message = "Failed to initialize, both AdminClient and AdminService are null";
		}
		NewRelic.getAgent().getLogger().log(Level.FINE, "Initialization sucessful, {0}", message);
	}

	private WSStats[] copyStats(WSStats[] stats) {
		WSStats[] wsStats = new WSStats[stats.length];

		for(int i=0;i<stats.length;i++) {
			try {
				wsStats[i] = copyStats((StatsImpl)stats[i]);
			} catch (Exception e) {
				NewRelic.getAgent().getLogger().log(Level.FINEST, e, "Failed to copy {0}", stats[i]);
				wsStats[i] = stats[i];
			} finally {
				stats[i] = null;
			}
		}
		return wsStats;
	}

	private static StatsImpl copyStats(StatsImpl stats) {
		if(stats == null) return null;

		ArrayList<StatsImpl> newSubStats = null;
		ArrayList<StatisticImpl> newStatistics = null;

		StatsImpl[] subStats = (StatsImpl[]) stats.listSubStats();

		if(subStats != null && subStats.length > 0) {
			newSubStats = new ArrayList<>();
			for (StatsImpl subStat : subStats) {
				newSubStats.add(copyStats(subStat));
			}
		}

		StatisticImpl[] statistics = (StatisticImpl[])stats.listStatistics();

		if(statistics != null && statistics.length > 0) {
			newStatistics = new ArrayList<>();
			Collections.addAll(newStatistics, statistics);
		}

		return new StatsImpl(stats.getStatsType(), stats.getName(), stats.getType(), stats.getLevel(), newStatistics, newSubStats);
	}

	@SuppressWarnings("rawtypes")
	@Override
	public void run() {
		if(!collect) return;

		if(!initialized) {
			try {
				init();
			} catch (AdminException e) {
				NewRelic.getAgent().getLogger().log(Level.INFO, e, "Failed to initialize PMISampler");
				failed++;
				if(failed == 10) {
					collect = false;
					NewRelic.getAgent().getLogger().log(Level.WARNING, "Have failed 10 times to initialize PMISampler, skipping PMI collection");
				}
				return;
			}
		}
		ArrayList<PMIMBeans> mbeans;

		try {
			mbeans = findPerfMBeans();
		} catch (Exception e) {
			NewRelic.getAgent().getLogger().log(Level.FINE, e, "Failed to find Perf beans");
			return;
		}

		Logger logger = NewRelic.getAgent().getLogger();
		String[] signature = new String[] {"[Lcom.ibm.websphere.pmi.stat.StatDescriptor;","java.lang.Boolean" };

		for(PMIMBeans pmiMBean : mbeans) {
			logger.log(Level.FINE, "Processing PMIMbeans {0}", pmiMBean);
			ObjectName perf = pmiMBean.perf;

			Object[] params = new Object[] {new StatDescriptor[] { (new StatDescriptor(null)) }, Boolean.TRUE };
			String type = perf.getKeyProperty("type");
			String process = perf.getKeyProperty("process");
			String node = perf.getKeyProperty("node");
			String cell = perf.getKeyProperty("cell");

			if (type != null && type.equals("Perf")) {
				PMIStat pmiStat = new PMIStat(process, node, cell);
				WSStats[] wsStats = null;

				if(!SecurityContext.isSecurityEnabled()) {
					try {
						if(ac != null) {
							wsStats = (WSStats[])ac.invoke(perf, "getStatsArray", params, signature);
						} else if(as != null) {
							wsStats = (WSStats[])as.invoke(perf, "getStatsArray", params, signature);
							if(wsStats != null) {
								wsStats = copyStats(wsStats);
							}
						}
					} catch (Exception e) {
						logger.log(Level.FINER, e, "PMICollector failed while getting stats");
						return;
					} 
				} else {
					final ObjectName _perf = perf;
					final Object[] _params = params;
					final String[] _signature = signature;

					PrivilegedExceptionAction action = new PrivilegedExceptionAction() {
						WSStats[] _wstats = null;

						public Object run() throws Exception {
							if(PMISampler.this.ac != null) {
								_wstats = (WSStats[])PMISampler.this.ac.invoke(_perf, "getStatsArray", _params, _signature);
							} else if(PMISampler.this.as != null) {
								_wstats = (WSStats[])PMISampler.this.as.invoke(_perf, "getStatsArray", _params, _signature);
								if(_wstats != null) {
									_wstats = PMISampler.this.copyStats(_wstats);
								}
							}

							return _wstats;
						}
					};

					try {
						wsStats = (WSStats[])SecurityContext.runAsSystem(action);

					} catch(PrivilegedActionException e) {
						NewRelic.getAgent().getLogger().log(Level.FINE, e, "Exception while executing priviledged action");
					}
				}

				logger.log(Level.FINEST,"Stats array has {0} elements", wsStats != null ? wsStats.length : 0);
				if (wsStats != null) {
					for (WSStats wsStat : wsStats) {
						logger.log(Level.FINEST, "Processing WSStat: {0}", wsStat.getName());
						processStat(pmiStat,wsStat);
					}
				}

			} else {
				logger.log(Level.FINER, "MBean {0} is not of type Perf, type: {1}", perf,type);
			}

		}

	}

	private String getPrefix(PMIStat pmiStat) {
		StringBuilder sb = new StringBuilder();
		if(pmiStat.process != null && !pmiStat.process.isEmpty()) {
			sb.append(pmiStat.process);
			sb.append('/');
		}
		if(pmiStat.node != null && !pmiStat.node.isEmpty()) {
			sb.append(pmiStat.node);
			sb.append('/');
		}
		if(pmiStat.cell != null && !pmiStat.cell.isEmpty()) {
			sb.append(pmiStat.cell);
			sb.append('/');
		}
		for(WSStats wsStats : pmiStat.parentStats) {
			String statName = wsStats.getName();
			if(statName != null && !statName.isEmpty()) {
				sb.append(statName);
				sb.append('/');
			}
		}
		return sb.toString();
	}

	@SuppressWarnings("rawtypes")
	private void processStat(PMIStat pmiStat,WSStats wsStat) {
		if(debug) {
			HashMap<String,Object> eventMap = new HashMap<>();

			eventMap.put("PmiStat-Process", pmiStat.process);
			eventMap.put("PmiStat-Node", pmiStat.node);
			eventMap.put("PmiStat-Cell", pmiStat.cell);
			ArrayList<WSStats> parentStats = pmiStat.parentStats;
			if (!parentStats.isEmpty()) {
				List<String> parents = new ArrayList<>();
				for (WSStats parentStat : parentStats) {
					parents.add(parentStat.getName());
				}
				eventMap.put("PmiStat-Parents", parents.toString());
			}
			eventMap.put("WSStat-Name", wsStat.getName());
			eventMap.put("WSStat-Type", wsStat.getStatsType());
			String[] statsNames = wsStat.getStatisticNames();
			if(statsNames != null && statsNames.length > 0) {
				eventMap.put("WSStat-StatisticNames", Arrays.toString(statsNames));
			}

			NewRelic.getAgent().getInsights().recordCustomEvent("ProcessStat", eventMap);
		}
		Logger logger = NewRelic.getAgent().getLogger();
		String prefix = getPrefix(pmiStat);
		String wsName = wsStat.getName();
		String wstype = wsStat.getStatsType();

		if(wsStat instanceof StatsImpl) {
			StatsImpl statsImpl = (StatsImpl)wsStat;
			ArrayList<?> stats = statsImpl.copyStatistics();
			if (include(wstype)) {
				if (stats != null) {
					for (Object object : stats) {
						StatisticImpl statImpl = (StatisticImpl) object;
                        if (sendToMetrics) {
                            if (!prefix.isEmpty()) {
                                reportMetric(prefix + wsName, statImpl);
                            } else {
                                reportMetric(wsName, statImpl);
                            }
                        }
                        if (sendToEvents) {
							PMIStat stat = new PMIStat(pmiStat, wsStat);
							reportEvent(stat, statImpl);
						}
					}
				}
			}
			ArrayList subStats = statsImpl.copyStats();
			if (subStats != null) {
				for (Object stat : subStats) {
					WSStats subStat = (WSStats) stat;
					PMIStat subPMI = new PMIStat(pmiStat, subStat);
					processStat(subPMI, subStat);
				}
			}
		} else {
			logger.log(Level.FINE, "wsStat is NOT an instance of StatsImpl, is {0}", wsStat);
		}
	}

	private boolean include(String statType) {
		if(stattype_filters == null || stattype_filters.isEmpty()) return true;
		NewRelic.getAgent().getLogger().log(Level.FINEST, "Will check {0} to see if it is in {1}", statType, stattype_filters);
		for(String s : stattype_filters) {
			if(statType != null && statType.equals(s)) return true;
			if(statType != null && statType.matches(s)) return true;
		}
		return false;
	}

	private boolean include(String statName,String metricName) {
		if(name_filters == null || name_filters.isEmpty()) return true;
		for(String s : name_filters) {
			if(statName != null && statName.equals(s)) return true;
			if(statName != null && statName.matches(s)) return true;
			if(metricName != null && metricName.equals(s)) return true;
			if(metricName != null && metricName.matches(s)) return true;
		}
		return false;
	}

	private void reportMetric(String wsName,StatisticImpl stat) {
		String statName = stat.getName();
		String metricName;
		if(statName != null) {
			metricName = "PMI/"+wsName+"/"+statName;
		} else {
			metricName = "PMI/"+wsName;
		}
		if(metricName.contains("//")) metricName = metricName.replace("//", "/");
		if(stat instanceof CountStatisticImpl) {
			CountStatisticImpl countStat = (CountStatisticImpl)stat;
			if(include(statName,metricName)) {
				NewRelic.recordMetric(metricName, countStat.getCount());
			}
		} else if(stat instanceof DoubleStatisticImpl) {
			DoubleStatisticImpl doubleStat = (DoubleStatisticImpl)stat;
			if(include(statName,metricName)) {
				NewRelic.recordMetric("PMI/"+wsName+"/"+statName, new Double(doubleStat.getDouble()).floatValue());
			}
		} else if(stat instanceof AverageStatisticImpl) {
			AverageStatisticImpl averageStat = (AverageStatisticImpl)stat;
			if(include(statName,metricName)) {
				NewRelic.recordMetric("PMI/"+wsName+"/"+statName+"/Count", new Long(averageStat.getCount()).floatValue());
				NewRelic.recordMetric("PMI/"+wsName+"/"+statName+"/Min", new Long(averageStat.getMin()).floatValue());
				NewRelic.recordMetric("PMI/"+wsName+"/"+statName+"/Max", new Long(averageStat.getMax()).floatValue());
				NewRelic.recordMetric("PMI/"+wsName+"/"+statName+"/Total", new Long(averageStat.getTotal()).floatValue());
			}
		} else if(stat instanceof BoundaryStatisticImpl) {
			BoundaryStatisticImpl boundaryStat = (BoundaryStatisticImpl)stat;
			if(include(statName,metricName)) {
				NewRelic.recordMetric("PMI/"+wsName+"/"+statName+"/LowerBound", new Long(boundaryStat.getLowerBound()).floatValue());
				NewRelic.recordMetric("PMI/"+wsName+"/"+statName+"/UpperBound", new Long(boundaryStat.getUpperBound()).floatValue());
			}
		} else if(stat instanceof RangeStatisticImpl) {
			RangeStatisticImpl rangeStat = (RangeStatisticImpl)stat;
			if(include(statName,metricName)) {
				NewRelic.recordMetric("PMI/"+wsName+"/"+statName+"/Current", new Long(rangeStat.getCurrent()).floatValue());
				NewRelic.recordMetric("PMI/"+wsName+"/"+statName+"/LowWaterMark", new Long(rangeStat.getLowWaterMark()).floatValue());
				NewRelic.recordMetric("PMI/"+wsName+"/"+statName+"/HighWaterMark", new Long(rangeStat.getHighWaterMark()).floatValue());
			}
		} else {
			NewRelic.getAgent().getLogger().log(Level.FINE, "No logic to handle stat type {0}", stat);
		}
	}

	private void reportNullName(PMIStat pmiStat, Object obj) {
		HashMap<String, Object> eventMap = new HashMap<>();
		eventMap.put("Process", pmiStat.process);
		eventMap.put("Node", pmiStat.node);
		eventMap.put("Cell", pmiStat.cell);
		if(pmiStat.wsStats != null) {
			eventMap.put("WSStat", pmiStat.wsStats);
		}
		if(!pmiStat.parentStats.isEmpty()) {
			int k = 0;
			for(WSStats wsStat : pmiStat.parentStats) {
				k++;
				eventMap.put("Parent-"+k, wsStat);
			}
		}
		eventMap.put("Null Statistic Name", obj);
		NewRelic.getAgent().getInsights().recordCustomEvent("PMIError", checkMap(eventMap));

	}

	private void reportEvent(PMIStat pmiStat,StatisticImpl stat) {
		String statName = stat.getName();
		if(statName == null && debug) {
			reportNullName(pmiStat, stat);
			return;
		}
		if(!include(statName,null)) return;

		if(pmiStat.wsStats != null) {
			if(!include(pmiStat.wsStats.getStatsType())) return;
		}
		HashMap<String, Object> eventMap = new HashMap<>();
		eventMap.put("Process", pmiStat.process);
		eventMap.put("Node", pmiStat.node);
		eventMap.put("Cell", pmiStat.cell);
		int count = 1;
		for(WSStats wsStats : pmiStat.parentStats) {
			String wsStatName = wsStats.getName();
			if(wsStatName != null && !wsStatName.isEmpty()) {
				String key = "WSStats" + count;
				count++;
				eventMap.put(key, wsStatName);
			}
		}
		if(pmiStat.wsStats != null) {
			eventMap.put("WSStats", pmiStat.wsStats.getName());
			eventMap.put("StatType", pmiStat.wsStats.getStatsType());
		}
		if(stat instanceof CountStatisticImpl) {
			CountStatisticImpl countStat = (CountStatisticImpl)stat;
			eventMap.put("Stat Name", statName);
			eventMap.put("Statistic Type", "CountStatistic");
			eventMap.put("Count", countStat.getCount());
		} else if(stat instanceof DoubleStatisticImpl) {
			DoubleStatisticImpl doubleStat = (DoubleStatisticImpl)stat;
			eventMap.put("Stat Name", statName);
			eventMap.put("Statistic Type", "DoubleStatistic");
			eventMap.put("Double", doubleStat.getDouble());
		} else if(stat instanceof AverageStatisticImpl) {
			AverageStatisticImpl averageStat = (AverageStatisticImpl)stat;
			eventMap.put("Stat Name", statName);
			eventMap.put("Statistic Type", "AverageStatistic");
			eventMap.put("Count", averageStat.getCount());
			eventMap.put("Minimum", averageStat.getMin());
			eventMap.put("Maximum", averageStat.getMax());
			eventMap.put("Total", averageStat.getTotal());
		} else if(stat instanceof BoundaryStatisticImpl) {
			BoundaryStatisticImpl boundaryStat = (BoundaryStatisticImpl)stat;
			eventMap.put("Stat Name", statName);
			eventMap.put("Statistic Type", "BoundaryStatistic");
			eventMap.put("Lower Bound", boundaryStat.getLowerBound());
			eventMap.put("Upper Bound", boundaryStat.getUpperBound());
		} else if(stat instanceof RangeStatisticImpl) {
			RangeStatisticImpl rangeStat = (RangeStatisticImpl)stat;
			eventMap.put("Stat Name", statName);
			eventMap.put("Statistic Type", "RangeStatistic");
			eventMap.put("Current", rangeStat.getCurrent());
			eventMap.put("Low Water Mark", rangeStat.getLowWaterMark());
			eventMap.put("High Water Mark", rangeStat.getHighWaterMark());
		} else {
			NewRelic.getAgent().getLogger().log(Level.FINE, "No logic to handle stat type {0}", stat);
			return;
		}
		NewRelic.getAgent().getInsights().recordCustomEvent("PMIEvent", checkMap(eventMap));
	}

	private HashMap<String, Object> checkMap(HashMap<String, Object> eventMap) {
		HashMap<String, Object> copyMap = new HashMap<>();

		Set<String> keys = eventMap.keySet();
		for(String key : keys) {
			if(key != null) {
				Object value = eventMap.get(key);
				if(value != null) {
					copyMap.put(key, value);
				}
			}
		}
		return copyMap;
	}

	@Override
	public void configChanged(String paramString, AgentConfig config) {
		processConfig(config);
	}

	private void processConfig(Config config) {

		Object val = config.getValue(ENABLED);
		if(val != null) {
			if(val instanceof Boolean) {
				collect = (Boolean) val;
			} else if (val instanceof String) {
				collect = Boolean.parseBoolean((String) val);
			} else {
				NewRelic.getAgent().getLogger().log(Level.FINE, "Found value for PMI Enabled but it is of type Boolean or String it is of type {0}", val.getClass().getName());
			}
		} else {
			collect = true;
		}
		NewRelic.getAgent().getLogger().log(Level.INFO, "PMI Enabled set to {0}", collect);

		val = config.getValue(METRICS_ENABLED);
		if(val != null) {
			if(val instanceof Boolean) {
				sendToMetrics = (Boolean)val;
			} else if(val instanceof String) {
				sendToMetrics = Boolean.parseBoolean((String)val);
			} else {
				NewRelic.getAgent().getLogger().log(Level.FINE, "Found value for PMI Metrics but it is of type Boolean or String it is of type {0}", val.getClass().getName());
			}
		} else {
				sendToMetrics = false;
		}
		NewRelic.getAgent().getLogger().log(Level.INFO, "PMI SendMetrics set to {0}", sendToMetrics);

		val = config.getValue(EVENTS_KEY);
		if(val != null) {
			if(val instanceof Boolean) {
				sendToEvents = (Boolean)val;
			} else if(val instanceof String) {
				sendToEvents = Boolean.parseBoolean((String)val);
			} else {
				NewRelic.getAgent().getLogger().log(Level.FINE, "Found value for PMI Events Enable but it is of type Boolean or String it is of type {0}", val.getClass().getName());
			}
		} else {
			if(!collect) {
				sendToEvents = false;
			}
		}
		NewRelic.getAgent().getLogger().log(Level.INFO, "PMI SendToEvents set to {0}", sendToEvents);
		val = config.getValue(STAT_TYPE_FILTER_LIST);
		if(val != null) {
			String value = val.toString();
			if(!value.isEmpty()) {
				String[] splits = value.split(",");
				stattype_filters = Arrays.asList(splits);
				NewRelic.getAgent().getLogger().log(Level.INFO, "PMI Stat Type Filter set to {0}", stattype_filters);
			} else {
				stattype_filters = null;
				NewRelic.getAgent().getLogger().log(Level.INFO, "PMI Stat Type Filter will not be used");
			}
		} else {
			stattype_filters = null;
			NewRelic.getAgent().getLogger().log(Level.INFO, "PMI Stat Type Filter will not be used");
		}
		val = config.getValue(NAME_FILTER_LIST);
		if(val != null) {
			String value = val.toString();
			if(!value.isEmpty()) {
				String[] splits = value.split(",");
				name_filters = Arrays.asList(splits);
				NewRelic.getAgent().getLogger().log(Level.INFO, "PMI Name Filter set to {0}", name_filters);
			} else {
				name_filters = null;
				NewRelic.getAgent().getLogger().log(Level.INFO, "PMI Name Filter will not be used");
			}
		} else {
			name_filters = null;
			NewRelic.getAgent().getLogger().log(Level.INFO, "PMI Name Filter will not be used");
		}
		val = config.getValue(DEBUG_MODE);
		if(val != null) {
			if(val instanceof Boolean) {
				debug = (Boolean)val;
			} else if(val instanceof String) {
				debug = Boolean.parseBoolean((String)val);
			} else {
				NewRelic.getAgent().getLogger().log(Level.FINE, "Found value for PMI debug but it is of type {0}", val.getClass().getName());
			}
		} else {
			if(debug) {
				debug = false;
			}
		}
	}

	private static class PMIStat {
		private final String process;
		private final String node;
		private final String cell;
		private final ArrayList<WSStats> parentStats = new ArrayList<>();
		private WSStats wsStats = null;

		public PMIStat(String process, String node, String cell) {
			super();
			this.process = process;
			this.node = node;
			this.cell = cell;
		}

		private PMIStat(PMIStat stat, WSStats ws_Stats) {
			process = stat.process;
			node = stat.node;
			cell = stat.cell;
			parentStats.addAll(stat.parentStats);
			if(stat.wsStats != null && !stat.wsStats.getName().equals(ws_Stats.getName())) {
				parentStats.add(stat.wsStats);
			}
			wsStats = ws_Stats;
		}

		@Override
		public String toString() {
			return "PMIStat:" + "process=" +
					process +
					",node=" +
					node +
					",cell=" +
					cell +
					",parents=" +
					parentStats +
					",wsStats=" +
					wsStats;
		}


	}

	private static class PMIMBeans {
		protected ObjectName server;
		protected ObjectName perf;

		public PMIMBeans(ObjectName server, ObjectName perf) {
			super();
			this.server = server;
			this.perf = perf;
		}

		@Override
		public String toString() {
			return "PMIMBeans [server=" + server + ", perf=" + perf + "]";
		}


	}
}
