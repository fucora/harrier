package cn.spdb.harrier.server.worker.rpc.transport;

import cn.spdb.harrier.rpc.common.RpcHandler;
import cn.spdb.harrier.rpc.common.RpcMethod;
import cn.spdb.harrier.server.entity.JobExecutionContext;

@RpcHandler("WorkTransportServerInterface")
public interface WorkTransportServerInterface {

	@RpcMethod
	Boolean dispathcer(JobExecutionContext job);

}