package cn.spdb.harrier.server.master;

import java.text.ParseException;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import javax.annotation.PostConstruct;
import javax.annotation.PreDestroy;

import org.apache.commons.lang3.ObjectUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.util.CollectionUtils;

import com.alibaba.fastjson.JSON;

import cn.spdb.harrier.common.Constants;
import cn.spdb.harrier.common.enmus.ExecutionStatus;
import cn.spdb.harrier.common.enmus.RegistryState;
import cn.spdb.harrier.common.enmus.UdsJobType;
import cn.spdb.harrier.common.model.JobSignal;
import cn.spdb.harrier.common.uitls.DateUtils;
import cn.spdb.harrier.common.uitls.Host;
import cn.spdb.harrier.common.uitls.IPUtils;
import cn.spdb.harrier.common.uitls.NameThreadFactory;
import cn.spdb.harrier.common.uitls.SnowFlakeBuidID;
import cn.spdb.harrier.common.uitls.Stopper;
import cn.spdb.harrier.common.uitls.Symbol;
import cn.spdb.harrier.dao.cache.SystemCache;
import cn.spdb.harrier.dao.entity.UdsJob;
import cn.spdb.harrier.dao.entity.UdsJobConfig;
import cn.spdb.harrier.dao.entity.UdsJobDateFrequency;
import cn.spdb.harrier.dao.entity.UdsJobDependency;
import cn.spdb.harrier.dao.entity.UdsJobRecord;
import cn.spdb.harrier.dao.entity.UdsJobSelfSignal;
import cn.spdb.harrier.dao.entity.UdsJobSource;
import cn.spdb.harrier.dao.entity.UdsJobStep;
import cn.spdb.harrier.dao.entity.UdsJobStepRecord;
import cn.spdb.harrier.dao.entity.UdsJobTimeTrigger;
import cn.spdb.harrier.dao.entity.UdsServer;
import cn.spdb.harrier.dao.entity.UdsSystem;
import cn.spdb.harrier.dao.mapper.UdsJobConfigMapper;
import cn.spdb.harrier.dao.mapper.UdsJobDateFrequencyMapper;
import cn.spdb.harrier.dao.mapper.UdsJobDependencyMapper;
import cn.spdb.harrier.dao.mapper.UdsJobMapper;
import cn.spdb.harrier.dao.mapper.UdsJobRecordMapper;
import cn.spdb.harrier.dao.mapper.UdsJobSelfSignalMapper;
import cn.spdb.harrier.dao.mapper.UdsJobSourceMapper;
import cn.spdb.harrier.dao.mapper.UdsJobStepMapper;
import cn.spdb.harrier.dao.mapper.UdsJobStepRecordMapper;
import cn.spdb.harrier.dao.mapper.UdsJobTimeTriggerMapper;
import cn.spdb.harrier.server.entity.JobExecutionContext;
import cn.spdb.harrier.server.entity.JobStepBean;
import cn.spdb.harrier.server.entity.WorkingInfo;
import cn.spdb.harrier.server.master.cache.MasterMangerWorker;
import cn.spdb.harrier.server.master.conf.MasterConfig;
import cn.spdb.harrier.server.master.deal.PlatformDealExecutor;
import cn.spdb.harrier.server.master.dispath.SelectManger;
import cn.spdb.harrier.server.master.rpc.MasterRpc;
import cn.spdb.harrier.server.master.weight.WeightManger;
import cn.spdb.harrier.service.db.DbRegistryService;

@Component
public class MasterManagerService {

	private static Logger logger = LoggerFactory.getLogger(MasterManagerService.class.getSimpleName());

	@Autowired
	private UdsJobMapper jobMapper;

	@Autowired
	private UdsJobConfigMapper jobConfigMapper;

	@Autowired
	private UdsJobDependencyMapper dependencyMapper;

	@Autowired
	private UdsJobStepMapper stepMapper;

	@Autowired
	private UdsJobDateFrequencyMapper frequencyMapper;

	@Autowired
	private UdsJobTimeTriggerMapper triggerMapper;

	@Autowired
	private UdsJobRecordMapper recordMapper;

	@Autowired
	private UdsJobStepRecordMapper stepRecordMapper;

	@Autowired
	private WeightManger weightManger;

	@Autowired
	private UdsJobSelfSignalMapper selfSignalMapper;

	@Autowired
	private MasterMangerWorker mangerWorker;

	@Autowired
	private UdsJobSourceMapper sourceMapper;

	@Autowired
	private SystemCache systemCache;

	@Autowired
	private MasterRpc masterRpc;

	private HashMap<String, PlatformDealExecutor> platformThreadMap = new HashMap<String, PlatformDealExecutor>();

	private ScheduledExecutorService scheduledService = Executors.newScheduledThreadPool(
			Runtime.getRuntime().availableProcessors(), new NameThreadFactory(this.getClass().getSimpleName()));

	@Autowired
	private MasterConfig masterConfig;

	@Autowired
	private DbRegistryService dbRegistryService;

	private UdsServer udsServer;

	private SnowFlakeBuidID buildID;

	@PostConstruct
	public void init() {
		UdsServer udsServer = new UdsServer();
		udsServer.setPort(masterConfig.getListenPort());
		udsServer.setServerName(masterConfig.getServerName());
		udsServer.setServerRoleName(masterConfig.getRoleName());
		udsServer.setServerRoleNameGroup(masterConfig.getRoleGroup());
		udsServer.setNodeClusterType(Constants.THREAD_NAME_MASTER_SERVER);
		udsServer.setIp(IPUtils.getHostIp());
		udsServer = dbRegistryService.registrydb(udsServer, event -> {
			if (event.getState().equals(RegistryState.CONNECTED) || event.getState().equals(RegistryState.DISCONNECTED)
					|| event.getState().equals(RegistryState.SUSPENDED)) {
				UdsServer udsServerTmp = event.getUdsServer();
				if (udsServerTmp.getNodeClusterType().equals(Constants.THREAD_NAME_MASTER_SERVER)) {
					if (udsServerTmp.equals(this.udsServer) && event.getState().equals(RegistryState.SUSPENDED)) {
						stopPlatfromsDeal();
					} else {
						UdsServer us = dbRegistryService.getMaster();
						if (us.equals(this.udsServer)) {
							startPlatfromsDeal();
						} else {
							stopPlatfromsDeal();
						}
					}
				}
			}

			if (event.getState().equals(RegistryState.DISCONNECTED)
					|| event.getState().equals(RegistryState.SUSPENDED)) {
				UdsServer udsServerTmp = event.getUdsServer();
				if (udsServerTmp.getNodeClusterType().equals(Constants.THREAD_NAME_WORKER_SERVER)) {
					WorkingInfo info = deleteWorkingInfo(new Host(udsServerTmp.getIp(), udsServerTmp.getPort()));
					if (!ObjectUtils.isEmpty(info)) {
						jobMapper.updateJobStatus(ExecutionStatus.UNKNOWN, ExecutionStatus.RUNING, null, null, null,
								info.getHost().getName());
					}
				}
			}
			logger.info(event.getIp() + ":" + event.getPort() + " " + event.getState().name());
		});
		logger.info("Registry Service address:{}", udsServer.getIp() + Symbol.MAO_HAO + udsServer.getPort());
		this.udsServer = udsServer;
		buildID = new SnowFlakeBuidID(udsServer.getId());
		scheduledService.scheduleWithFixedDelay(() -> {
			try {
				selectPendingOrDispatcherJobOfOverTime();
			} catch (Exception e) {
				e.printStackTrace();
			}
		}, 1, 1, TimeUnit.MINUTES);

		scheduledService.scheduleWithFixedDelay(() -> {
			try {
				selectScheduleJobDb();
			} catch (Exception e) {
				e.printStackTrace();
			}
		}, 10, 10, TimeUnit.SECONDS);
	}

	@PreDestroy
	public void close() {
		logger.info("master thread stop start");
		scheduledService.shutdown();
		Stopper.stop();
		platformThreadMap.values().forEach(exe -> exe.shutdown());
		logger.info("master thread stop end");
	}

	public void startPlatfromsDeal() {
		platformThreadMap.values().forEach(exe -> exe.start());
	}

	public void stopPlatfromsDeal() {
		platformThreadMap.values().forEach(exe -> exe.stop());
	}

	public Boolean streamJobSignal(JobSignal jobSignal) {
		logger.info("out stream job signal {}", jobSignal);
		JobExecutionContext jobContext = createJobExecutionContext(jobSignal);
		if (ObjectUtils.isEmpty(jobContext)) {
			logger.error("create job executioncontext context error,out stream job signal: {}", jobSignal);
			return false;
		}
		boolean pass = checkJobStatusAndFrequency(jobContext, jobSignal);
		logger.error("check job status and frequency is not pass,out stream job signal: {}", jobSignal);
		if (pass) {
			conversionPending(jobContext, jobSignal);
			logger.info("job {} conversion pending", jobContext);
			return true;
		}
		return false;
	}

	private JobExecutionContext createJobExecutionContext(JobSignal jobSignal) {
		UdsJob job = jobMapper.selectBySignal(jobSignal.getSignal()).orElse(null);
		if (ObjectUtils.isEmpty(job)) {
			logger.error("db udssource or udsjob of job is null,out stream job signal {}", jobSignal);
			return null;
		}
		UdsSystem system = systemCache.getUdsSystemByUseful(job.getPlatform(), job.getSystems());
		if (ObjectUtils.isEmpty(system)) {
			logger.error("db table uds system column platform is null,out stream job signal: {} , platform: {}",
					jobSignal, job.getPlatform());
			return null;
		}
		UdsJobConfig jobConfig = jobConfigMapper.selectBySignal(jobSignal.getSignal()).orElse(null);
		if (ObjectUtils.isEmpty(jobConfig)) {
			logger.error("db table uds config column platform is null,out stream job signal {}, platform:"
					+ "{} systems:{} job:{}", jobSignal, job.getPlatform(), job.getSystems(), job.getJob());
			return null;
		}
		JobExecutionContext jobContext = createJobExecutionContext(job, jobConfig);
		return jobContext;
	}

	private JobExecutionContext createJobExecutionContext(UdsJob job, UdsJobConfig jobConfig) {
		JobExecutionContext jobContext = new JobExecutionContext();
		jobContext.setUdsJobConfig(jobConfig);
		jobContext.setUdsJob(job);
		return jobContext;
	}

	private boolean checkJobStatusAndFrequency(JobExecutionContext jobContext, JobSignal jobSignal) {
		if (jobContext.getUdsJobConfig().getJobType().equals(UdsJobType.C.name())) {
			logger.error("job is scheduled job,type is C, job:{}", jobContext);
			return false;
		}
		if (jobContext.getExecutionStatus().equals(ExecutionStatus.READY)) {
			return true;
		}
		if (!jobContext.getExecutionStatus().equals(ExecutionStatus.SUCCESS)) {
			logger.warn("job status is not SUCCESS, job:{}", jobContext);
			return false;
		}
		if (jobSignal.getBatch() > 0 && jobContext.getUdsJob().getMultiBatch() > 0) {
			if (jobSignal.getJobDate().equals(jobContext.getJobDate())) {
				if (jobSignal.getBatch() - jobContext.getUdsJob().getMultiBatch() == 1) {
					return true;
				} else if (jobSignal.getBatch() - jobContext.getUdsJob().getMultiBatch() > 1) {
					// weilai
					return false;
				} else {
					// lishi
					return false;
				}
			}
		} else {
			if (jobSignal.getBatch() - jobContext.getUdsJob().getMultiBatch() != 0) {
				logger.warn("jobSignal batch is error, job batch is 0, job:{}", jobContext);
				return false;
			}
		}

		if (jobContext.getNextJobDate().compareTo(jobSignal.getJobDate()) != 0) {
			LocalDate nextDay = calculateJobNextJobDate(jobContext);
			if (ObjectUtils.isEmpty(nextDay)) {
				logger.warn("job calculate next job date is error , job:{}", jobContext);
				return false;
			}
			jobContext.getUdsJob().setNextJobDate(nextDay);
			if (jobContext.getNextJobDate().compareTo(jobSignal.getJobDate()) != 0) {
				if (jobContext.getNextJobDate().compareTo(jobSignal.getJobDate()) < 0) {
					// 未来
				} else {
					// lishi
				}
				logger.warn(
						"job calculate next job date is not run job date ,  next job date:{}"
								+ "job signal date:{} ,job:{}",
						jobContext.getNextJobDate(), jobSignal.getJobDate(), jobContext);
				return false;
			}
		}

		if (jobSignal.getBatch() > 1) {
			// weilai
			logger.warn("job calculate next job date is true but batch is error,job signal batch{},job:{}",
					jobSignal.getBatch(), jobContext);
			return false;
		}
		return true;
	}

	private LocalDate calculateJobNextJobDate(JobExecutionContext jobContext) {
		LocalDate nextDay = null;
		switch (UdsJobType.valueOf(jobContext.getUdsJobConfig().getJobType())) {
		case D: {
			nextDay = jobContext.getUdsJob().getJobDate().plusDays(1);
			break;
		}
		case Y:
		case W:
		case M: {
			List<UdsJobDateFrequency> frequencies = frequencyMapper.select(jobContext.getPlatform(),
					jobContext.getSystem(), jobContext.getJob());
			if (frequencies.size() > 0) {
				String[] crons = new String[frequencies.size()];
				for (int i = 0; i < crons.length; i++) {
					crons[0] = frequencies.get(i).getCrontab();
				}
				nextDay = DateUtils.getNextValidTime(crons, jobContext.getUdsJob().getJobDate());
			}
			break;
		}
		case C: {
			List<UdsJobTimeTrigger> triggers = triggerMapper.select(jobContext.getPlatform(), jobContext.getSystem(),
					jobContext.getJob());
			if (triggers.size() > 0) {
				String[] crons = new String[triggers.size()];
				for (int i = 0; i < crons.length; i++) {
					crons[0] = triggers.get(i).getCrontab();
				}
				nextDay = DateUtils.getNextValidTime(crons,
						jobContext.getUdsJob().getJobDate().plusDays(-1 * jobContext.getUdsJobConfig().getOffsetDay()));
				nextDay = nextDay.plusDays(jobContext.getUdsJobConfig().getOffsetDay());
			}
		}
			break;
		default:
			break;
		}
		if (ObjectUtils.isEmpty(nextDay)) {
			return null;
		}
		return nextDay;
	}

	public boolean conversionPending(JobExecutionContext jobContext, JobSignal jobSignal) {
		jobContext.setGloParams(jobSignal.getParams());
		jobContext.setGloEnvs(jobSignal.getEnvs());
		jobContext.setBatch(jobSignal.getBatch());
		jobContext.setJobDate(jobSignal.getJobDate());
		conversionPending(jobContext);
		return true;
	}

	public void conversionPending(JobExecutionContext jobContext) {
		jobContext.setExecutionStatus(ExecutionStatus.PENDING);
		jobContext.getUdsJob().setPendingTime(LocalDateTime.now());
		jobContext.getUdsJob().setNumTimes(jobContext.getUdsJob().getNumTimes() + 1);
		jobMapper.updateByPrimaryKeySelective(jobContext.getUdsJob());
		logger.info("job conversion pending,job:{}", jobContext);
		addPendingQueue(jobContext);
	}

	public void updateJobStatus(ExecutionStatus newStatus, ExecutionStatus oldStatus, String serverName) {
		jobMapper.updateJobStatus(newStatus, oldStatus, null, null, null, serverName);
		logger.info("job update dispatcher,serverName:{} oldstatus:{} newStatus:{}", serverName, oldStatus, newStatus);
	}

	public void updateJobStatus(ExecutionStatus newStatus, String platform, String systems, String job) {
		jobMapper.updateJobStatus(newStatus, null, platform, systems, job, null);
		logger.info("job update dispatcher,platform:{} system:{} job:{} newStatus:{}", platform, systems, job,
				newStatus);
	}

	public void conversionDispatcher(JobExecutionContext jobContext) {
		jobContext.setExecutionStatus(ExecutionStatus.DISPATCHER);
		jobContext.getUdsJob().setDispatcherTime(LocalDateTime.now());
		jobMapper.updateByPrimaryKeySelective(jobContext.getUdsJob());
		logger.info("job conversion dispatcher,job:{}", jobContext);
	}

	public boolean checkJobWinds(UdsJobConfig jobConfig) {
		return DateUtils.isTimeWindowRang(jobConfig.getTimeWindow());
	}

	public boolean checkDependency(UdsJob job) {
		List<UdsJob> depencyList = jobMapper.getDependency(job.getPlatform(), job.getSystems(), job.getJob());
		for (UdsJob depJob : depencyList) {
			if (depJob.getLastStatus().equals(ExecutionStatus.READY.name())) {
				continue;
			}
			if (depJob.getMultiBatch() == 0) {
				if (depJob.getNextJobDate().compareTo(job.getJobDate()) > 0) {
					continue;
				}
			} else {
				if (job.getMultiBatch() == 0) {
					Optional<UdsJobDependency> optDeo = dependencyMapper.getDependencyBatch(job.getPlatform(),
							job.getSystems(), job.getJob(), depJob.getPlatform(), depJob.getSystems(), depJob.getJob());
					if (!optDeo.isPresent()) {
						continue;
					}
					int depBatch = optDeo.get().getDepBatch();
					if (depJob.getMultiBatch() > depBatch || (depJob.getMultiBatch() == depBatch
							&& depJob.getLastStatus().equals(ExecutionStatus.SUCCESS.name()))) {
						if (depJob.getNextJobDate().compareTo(job.getJobDate()) > 0) {
							continue;
						}
					}
					if (depJob.getMultiBatch() < depBatch && depJob.getJobDate().compareTo(job.getJobDate()) > 0) {
						continue;
					}
				} else {
					if (depJob.getMultiBatch() > job.getMultiBatch() || (depJob.getMultiBatch() == job.getMultiBatch()
							&& depJob.getLastStatus().equals(ExecutionStatus.SUCCESS.name()))) {
						if (depJob.getNextJobDate().compareTo(job.getJobDate()) > 0) {
							continue;
						}
					}
					if (depJob.getMultiBatch() < job.getMultiBatch()
							&& depJob.getJobDate().compareTo(job.getJobDate()) > 0) {
						continue;
					}
				}
			}
			return false;
		}
		return true;
	}

	public void incrementWeightAndJob(Host host, JobExecutionContext jobContext) {
		WorkingInfo workingInfo = mangerWorker.getWork(host);
		workingInfo.incrementJob(jobContext);
	}

	public void decrementWeightAndJob(Host host, JobExecutionContext jobContext) {
		WorkingInfo workingInfo = mangerWorker.getWork(host);
		if (ObjectUtils.isNotEmpty(workingInfo)) {
			workingInfo.decrementJob(jobContext);
		}
	}

	public Host passDispatcherGetHost(JobExecutionContext jobContext) {
		UdsSystem udsSystem = systemCache.getUdsSystemByUseful(jobContext.getPlatform(), jobContext.getSystem());
		int jobNum = mangerWorker.getJobNumByPlatformAndSystem(udsSystem.getPlatform(), udsSystem.getSystems());
		if (udsSystem.getMaxRunJob() <= jobNum) {
			logger.info("udsSystem is max job num: {} < run job num: {}", udsSystem.getMaxRunJob(), jobNum);
			return null;
		}
		Collection<WorkingInfo> sourceWorks = weightManger.check(jobContext, mangerWorker.getWorkList());
		if (CollectionUtils.isEmpty(sourceWorks)) {
			logger.info("job is check weight is not pass, job {}", jobContext);
			return null;
		}
		Host host = SelectManger.getInstance().select(sourceWorks, udsSystem);
		return host;
	}

	public boolean sendJobExecutionContext(JobExecutionContext jobContext) {
		Host host = jobContext.getHost();
		if (ObjectUtils.isEmpty(host))
			return false;
		return masterRpc.getWorkClient(host).dispathcer(jobContext);
	}

	public List<JobStepBean> getJobStepList(String platform, String system, String job) {
		List<JobStepBean> jobStepBeanList = new ArrayList<JobStepBean>();
		List<UdsJobStep> stepList = stepMapper.selectJobStepList(platform, system, job);
		for (UdsJobStep step : stepList) {
			JobStepBean jobStepBean = build(step);
			jobStepBeanList.add(jobStepBean);
		}
		return jobStepBeanList;
	}

	private JobStepBean build(UdsJobStep step) {
		JobStepBean jobStepBean = new JobStepBean();
		jobStepBean.setStepType(step.getStepType());
		jobStepBean.setCmd(step.getOperCmd());
		jobStepBean.setEnvs(step.getEnvironments());
		jobStepBean.setPararmeter(step.getParameter());
		jobStepBean.setWorkdir(step.getWorkDir());
		jobStepBean.setStepNum(step.getStepNum());
		jobStepBean.setStepPath(step.getScriptPath());
		jobStepBean.setUpdateTime(step.getUpdateTime());
		return jobStepBean;
	}

	public boolean conversionSuccess(JobExecutionContext jobContext) {
		jobContext.setExecutionStatus(ExecutionStatus.SUCCESS);
		jobContext.getUdsJob().setEndTime(LocalDateTime.now());
		jobContext.getUdsJob().setNextJobDate(calculateJobNextJobDate(jobContext));
		jobMapper.updateByPrimaryKeySelective(jobContext.getUdsJob());
		logger.info("job conversion success,job:{}", jobContext);
		return true;
	}

	public boolean conversionSuccessAfter(JobExecutionContext jobContext) {
		addDepStreamJobDealQueue(jobContext);
		addSelfStreamJobDealQueue(jobContext);
		addDispatcherDealSignal(jobContext.getPlatform());
		return true;
	}

	public boolean conversionFailure(JobExecutionContext jobContext) {
		jobContext.setExecutionStatus(ExecutionStatus.FAILURE);
		jobContext.getUdsJob().setEndTime(LocalDateTime.now());
		jobMapper.updateByPrimaryKeySelective(jobContext.getUdsJob());
		logger.info("job conversion failure,job:{}", jobContext);
		return true;
	}

	public Boolean conversionRuning(JobExecutionContext jobContext) {
		jobContext.getUdsJob().setStartTime(LocalDateTime.now());
		jobContext.setExecutionStatus(ExecutionStatus.RUNING);
		logger.info("job conversion runing,job:{}", jobContext);
		jobMapper.updateByPrimaryKeySelective(jobContext.getUdsJob(), ExecutionStatus.DISPATCHER);
		Optional<UdsJob> optional = jobMapper.selectByPrimaryKey(jobContext.getUdsJob().getId());
		if (optional.isPresent()
				&& Math.abs(optional.get().getUpdateTime().getSecond() - LocalDateTime.now().getSecond()) < 3
				&& optional.get().getServerName().equals(jobContext.getUdsJob().getServerName())) {
			return true;
		}
		return false;
	}

	public void selfStream(JobExecutionContext jobContext) {
		if (!jobContext.getUdsJobConfig().getCheckStreamSelf()) {
			return;
		}
		Optional<UdsJobSource> optional = sourceMapper.selectOne(jobContext.getPlatform(), jobContext.getSystem(),
				jobContext.getJob());
		if (optional.isPresent()) {
			LocalDate jobDate = jobContext.getUdsJob().getJobDate();
			Integer batch = 0;
			Optional<UdsJobSelfSignal> optSelf = null;
			if (jobContext.getUdsJob().getMultiBatch() == 0) {
				jobDate = jobContext.getUdsJob().getNextJobDate();
				optSelf = selfSignalMapper.selectOneSignal(jobContext.getPlatform(), jobContext.getSystem(),
						jobContext.getJob(), jobDate, batch);
			} else {
				batch = jobContext.getUdsJob().getMultiBatch() + 1;
				optSelf = selfSignalMapper.selectOneSignal(jobContext.getPlatform(), jobContext.getSystem(),
						jobContext.getJob(), jobDate, batch);
				if (!optSelf.isPresent()) {
					batch = 1;
					jobDate = jobContext.getUdsJob().getNextJobDate();
					optSelf = selfSignalMapper.selectOneSignal(jobContext.getPlatform(), jobContext.getSystem(),
							jobContext.getJob(), jobDate, batch);
				}
			}
			if (optSelf.isPresent()) {
				jobContext.getUdsJob().setMultiBatch(batch);
				jobContext.getUdsJob().setJobDate(jobDate);
				logger.info("job self Stream ,job:{}", jobContext);
				conversionPending(jobContext);
			}
		} else {
			boolean isStream;
			if (jobContext.getUdsJob().getMultiBatch() == 0) {
				jobContext.getUdsJob().setJobDate(jobContext.getUdsJob().getNextJobDate());
				isStream = checkDependency(jobContext.getUdsJob());
			} else {
				int tmpBatch = jobContext.getUdsJob().getMultiBatch();
				jobContext.getUdsJob().setMultiBatch(tmpBatch + 1);
				if (checkDependency(jobContext.getUdsJob())) {
					isStream = true;
				} else {
					jobContext.getUdsJob().setJobDate(jobContext.getUdsJob().getNextJobDate());
					jobContext.getUdsJob().setMultiBatch(1);
					isStream = checkDependency(jobContext.getUdsJob());
				}
			}
			if (isStream) {
				jobContext.setExecutionStatus(ExecutionStatus.PENDING);
				jobContext.getUdsJob().setPendingTime(LocalDateTime.now());
				jobContext.getUdsJob().setNumTimes(jobContext.getUdsJob().getNumTimes() + 1);
				jobMapper.updateByPrimaryKeySelective(jobContext.getUdsJob());
				logger.info("job self Stream ,job:{}", jobContext);
				if (!checkJobWinds(jobContext.getUdsJobConfig())) {
					return;
				}
				conversionDispatcher(jobContext);
				addDispatcherDealQueue(jobContext);
			}
		}
	}

	public void depStream(JobExecutionContext jobContext) {
		Integer multiBatch = jobContext.getUdsJob().getMultiBatch();
		List<UdsJobConfig> udsjobList = jobConfigMapper.selectByDepAndCondition(jobContext.getPlatform(),
				jobContext.getSystem(), jobContext.getJob(), multiBatch);
		for (UdsJobConfig targetJobConfig : udsjobList) {
			Optional<UdsJobSource> optional = sourceMapper.selectOne(targetJobConfig.getPlatform(),
					targetJobConfig.getSystems(), targetJobConfig.getJob());
			if (optional.isPresent()) {
				continue;
			}
			UdsJob targetJob = jobMapper
					.selectOne(targetJobConfig.getPlatform(), targetJobConfig.getSystems(), targetJobConfig.getJob())
					.orElse(null);
			if (targetJob == null) {
				continue;
			}
			JobExecutionContext targetJobContext = createJobExecutionContext(targetJob, targetJobConfig);
			if (targetJob.getLastStatus().compareTo(ExecutionStatus.PENDING.name()) == 0) {
				addPendingQueue(targetJobContext);
				continue;
			}
			if (!(targetJob.getLastStatus().compareTo(ExecutionStatus.SUCCESS.name()) == 0
					|| targetJob.getLastStatus().compareTo(ExecutionStatus.READY.name()) == 0)) {
				continue;
			}
			// 依赖传递批次
			Integer targetBatch = targetJob.getMultiBatch();
			if (multiBatch == 0 && targetBatch > 0) {
				// 错误 单触发多
				continue;
			} else if (multiBatch == 0 && targetBatch == 0) {
			} else if (multiBatch > 0 && targetBatch > 0) {
				if (multiBatch % targetJobConfig.getBatchConversion() == 0) {
					int batch = multiBatch / targetJobConfig.getBatchConversion();
					if (batch - targetBatch == 1) {
						targetJob.setMultiBatch(batch);
					}
				}
				continue;
			} else if (multiBatch > 0 && targetBatch == 0) {
				Optional<UdsJobDependency> optDeo = dependencyMapper.getDependencyBatch(targetJob.getPlatform(),
						targetJob.getSystems(), targetJob.getJob(), jobContext.getPlatform(), jobContext.getSystem(),
						jobContext.getJob());
				if (!optDeo.isPresent()) {
					continue;
				}
				int depBatch = optDeo.get().getDepBatch();
				if (multiBatch != depBatch) {
					continue;
				}
			}

			// 日期符合性判断
			LocalDate localDate = jobContext.getUdsJob().getJobDate();
			if (localDate.compareTo(targetJob.getNextJobDate()) < 0) {
				continue;
			}
			targetJobContext.getUdsJob().setJobDate(targetJobContext.getUdsJob().getNextJobDate());
			targetJobContext.setGloEnvs(jobContext.getGloEnvs());
			targetJobContext.setGloParams(jobContext.getGloParams());
			if (!checkDependency(targetJobContext.getUdsJob())) {
				continue;
			}
			logger.info("job dep Stream ,job:{}", jobContext);
			jobContext.setExecutionStatus(ExecutionStatus.PENDING);
			jobContext.getUdsJob().setPendingTime(LocalDateTime.now());
			jobContext.getUdsJob().setNumTimes(jobContext.getUdsJob().getNumTimes() + 1);
			jobMapper.updateByPrimaryKeySelective(jobContext.getUdsJob());
			if (!checkJobWinds(targetJobContext.getUdsJobConfig())) {
				continue;
			}
			conversionDispatcher(targetJobContext);
			addDispatcherDealQueue(jobContext);
		}
	}

	public void addPendingQueue(JobExecutionContext jobContext) {
		PlatformDealExecutor platformDealExecutor = platformThreadMap.get(jobContext.getPlatform());
		if (platformDealExecutor == null) {
			platformDealExecutor = new PlatformDealExecutor(this);
			platformDealExecutor.setName(jobContext.getPlatform());
			platformDealExecutor.execute();
			platformThreadMap.put(jobContext.getPlatform(), platformDealExecutor);
		}
		platformDealExecutor.addPendingQueue(jobContext);
	}

	public void addDispatcherDealQueue(JobExecutionContext jobContext) {
		PlatformDealExecutor platformDealExecutor = platformThreadMap.get(jobContext.getPlatform());
		if (platformDealExecutor == null) {
			platformDealExecutor = new PlatformDealExecutor(this);
			platformDealExecutor.setName(jobContext.getPlatform());
			platformDealExecutor.execute();
			platformThreadMap.put(jobContext.getPlatform(), platformDealExecutor);
		}
		platformDealExecutor.addDispatcherDealQueue(jobContext);
	}

	public void addSelfStreamJobDealQueue(JobExecutionContext jobContext) {
		PlatformDealExecutor platformDealExecutor = platformThreadMap.get(jobContext.getPlatform());
		if (platformDealExecutor == null) {
			platformDealExecutor = new PlatformDealExecutor(this);
			platformDealExecutor.setName(jobContext.getPlatform());
			platformDealExecutor.execute();
			platformThreadMap.put(jobContext.getPlatform(), platformDealExecutor);
		}
		platformDealExecutor.addSelfStreamJobDealQueue(jobContext);
	}

	public void addDispatcherDealSignal(String platfrom) {
		PlatformDealExecutor platformDealExecutor = platformThreadMap.get(platfrom);
		if (!ObjectUtils.isEmpty(platformDealExecutor)) {
			platformDealExecutor.addReadyDispatcherDealSignal(platfrom);
		}
	}

	public void addDepStreamJobDealQueue(JobExecutionContext jobContext) {
		PlatformDealExecutor platformDealExecutor = platformThreadMap.get(jobContext.getPlatform());
		if (platformDealExecutor == null) {
			platformDealExecutor = new PlatformDealExecutor(this);
			platformDealExecutor.setName(jobContext.getPlatform());
			platformDealExecutor.execute();
			platformThreadMap.put(jobContext.getPlatform(), platformDealExecutor);
		}
		platformDealExecutor.addDepStreamJobDealQueue(jobContext);
	}

	public void selectPendingOrDispatcherJobOfOverTime() {
		try {
			List<UdsJob> list = jobMapper.selectOverTimePendingOrDispathcer();
			for (UdsJob job : list) {
				UdsJobConfig jobConfig = jobConfigMapper.selectOne(job.getPlatform(), job.getSystems(), job.getJob())
						.orElse(null);
				if (ObjectUtils.isEmpty(jobConfig)) {
					continue;
				}
				JobExecutionContext jobContext = createJobExecutionContext(job, jobConfig);
				if (jobContext.getExecutionStatus().equals(ExecutionStatus.PENDING)) {
					addPendingQueue(jobContext);
				}
				if (jobContext.getExecutionStatus().equals(ExecutionStatus.DISPATCHER)) {
					addDispatcherDealQueue(jobContext);
				}
			}
		} catch (Exception e) {
			e.printStackTrace();
		}
	}

	public void selectScheduleJobDb() {
		try {
			List<UdsJobTimeTrigger> list = triggerMapper.selectJobToScheduleTime();
			for (UdsJobTimeTrigger jobTimeTrigger : list) {
				UdsJobConfig jobConfig = jobConfigMapper
						.selectOne(jobTimeTrigger.getPlatform(), jobTimeTrigger.getSystems(), jobTimeTrigger.getJob())
						.orElse(null);
				if (ObjectUtils.isEmpty(jobConfig) || !jobConfig.getIsEnable()
						|| !jobConfig.getJobType().equals(UdsJobType.C.name()) || !jobConfig.getCheckTimeTrigger()) {
					continue;
				}
				UdsJob job = jobMapper
						.selectOne(jobTimeTrigger.getPlatform(), jobTimeTrigger.getSystems(), jobTimeTrigger.getJob())
						.orElse(null);
				if (ObjectUtils.isEmpty(job)) {
					continue;
				}
				JobExecutionContext jobContext = createJobExecutionContext(job, jobConfig);
				jobContext.setTimeCorn(jobTimeTrigger.getCrontab());
				LocalDate targetDate = jobTimeTrigger.getStartTime().plusDays(jobConfig.getOffsetDay()).toLocalDate();
				Boolean pass = true;
				if (jobContext.getExecutionStatus().equals(ExecutionStatus.READY)) {
					if (job.getMultiBatch() > 0) {
						job.setMultiBatch(1);
						job.setJobDate(targetDate);
					} else {
						job.setJobDate(targetDate);
					}
				} else if (jobContext.getExecutionStatus().equals(ExecutionStatus.SUCCESS)) {
					if (job.getMultiBatch() > 0) {
						if (targetDate.compareTo(job.getJobDate()) == 0) {
							job.setMultiBatch(job.getMultiBatch() + 1);
						} else if (targetDate.compareTo(job.getJobDate()) > 0) {
							job.setMultiBatch(1);
							job.setJobDate(targetDate);
						} else {
							logger.warn("执行日期不对 作业:{} 当前日期:{} 执行日期:{} ", job.getJob(), job.getJobDate(), targetDate);
							pass = false;
						}
					} else if (job.getMultiBatch() == 0) {
						if (targetDate.compareTo(job.getJobDate()) > 0) {
							job.setJobDate(targetDate);
						} else {
							logger.warn("执行日期不对 作业:{} 当前日期:{} 执行日期:{} ", job.getJob(), job.getJobDate(), targetDate);
							pass = false;
						}
					}
				} else {
					continue;
				}
				try {
					LocalDateTime nextStart = DateUtils.getNextValidTime(jobTimeTrigger.getCrontab(),
							jobTimeTrigger.getStartTime());
					jobTimeTrigger.setStartTime(nextStart);
					triggerMapper.updateByPrimaryKeySelective(jobTimeTrigger);
				} catch (ParseException e) {
					e.printStackTrace();
				}
				if (pass) {
					conversionPending(jobContext);
				}
			}
		} catch (Exception e) {
			e.printStackTrace();
		}
	}

	public void updateWorkingInfo(WorkingInfo workingInfo) {
		mangerWorker.updateWork(workingInfo);
	}

	public WorkingInfo deleteWorkingInfo(Host host) {
		return mangerWorker.deleteWork(host);
	}

	public void insertJobRecord(JobExecutionContext jobContext) {
		UdsJobRecord record = new UdsJobRecord();
		record.setId(jobContext.getTaskInstanceId());
		record.setPlatform(jobContext.getPlatform());
		record.setSystems(jobContext.getSystem());
		record.setJob(jobContext.getJob());
		record.setDispatcherTime(jobContext.getUdsJob().getDispatcherTime());
		record.setJobDate(jobContext.getUdsJob().getJobDate());
		record.setJobType(jobContext.getUdsJobConfig().getJobType());
		record.setLastStatus(jobContext.getUdsJob().getLastStatus());
		record.setMultiBatch(jobContext.getUdsJob().getMultiBatch());
		record.setNumTimes(jobContext.getUdsJob().getNumTimes());
		record.setPendingTime(jobContext.getUdsJob().getPendingTime());
		record.setStartTime(jobContext.getUdsJob().getStartTime());
		record.setEndTime(jobContext.getUdsJob().getEndTime());
		record.setServerName(jobContext.getUdsJob().getServerName());
		record.setVirtualEnable(jobContext.getUdsJobConfig().getVirtualEnable());
		record.setStreamType(jobContext.getUdsJob().getStreamType());
		recordMapper.insertSelective(record);
	}

	public void updateJobRecord(JobExecutionContext jobContext) {
		UdsJobRecord record = new UdsJobRecord();
		record.setId(jobContext.getTaskInstanceId());
		record.setPlatform(jobContext.getPlatform());
		record.setSystems(jobContext.getSystem());
		record.setJob(jobContext.getJob());
		record.setDispatcherTime(jobContext.getUdsJob().getDispatcherTime());
		record.setJobDate(jobContext.getUdsJob().getJobDate());
		record.setJobType(jobContext.getUdsJobConfig().getJobType());
		record.setLastStatus(jobContext.getUdsJob().getLastStatus());
		record.setMultiBatch(jobContext.getUdsJob().getMultiBatch());
		record.setNumTimes(jobContext.getUdsJob().getNumTimes());
		record.setPendingTime(jobContext.getUdsJob().getPendingTime());
		record.setStartTime(jobContext.getUdsJob().getStartTime());
		record.setEndTime(jobContext.getUdsJob().getEndTime());
		record.setServerName(jobContext.getUdsJob().getServerName());
		record.setVirtualEnable(jobContext.getUdsJobConfig().getVirtualEnable());
		record.setStreamType(jobContext.getUdsJob().getStreamType());
		recordMapper.updateByPrimaryKeySelective(record);
	}

	public void insertUdsJobStepRecord(UdsJobStepRecord record) {
		stepRecordMapper.insertSelective(record);
	}

	public void updateJobStepRecord(UdsJobStepRecord record) {
		stepRecordMapper.updateByPrimaryKeySelective(record);
	}

	public long getSingleId() {
		if (ObjectUtils.isEmpty(buildID)) {
			buildID = new SnowFlakeBuidID();
		}
		return buildID.getNextId();
	}

	public void insertSelfSignal(JobSignal jobSignal, String platform, String systems, String job) {
		UdsJobSelfSignal record = new UdsJobSelfSignal();
		record.setPlatform(platform);
		record.setSystems(systems);
		record.setJob(job);
		record.setBatch(jobSignal.getBatch());
		record.setJobDate(
				LocalDate.parse(jobSignal.getJobDate(), DateTimeFormatter.ofPattern(DateUtils.PATTERN_YYYYMMDD_CONS)));
		record.setEvns(JSON.toJSONString(jobSignal.getEnvs()));
		record.setParams(JSON.toJSONString(jobSignal.getParams()));
		record.setUseful(true);
		selfSignalMapper.insertSelective(record);
	}

}