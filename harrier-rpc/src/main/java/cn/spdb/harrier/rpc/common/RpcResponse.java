package cn.spdb.harrier.rpc.common;

public class RpcResponse {

	private Byte status;
	private String msg;
	private Object result;
//	private Byte eventType;


	public String getMsg() {
		return msg;
	}

	public void setMsg(String msg) {
		this.msg = msg;
	}

	public Object getResult() {
		return result;
	}

	public void setResult(Object result) {
		this.result = result;
	}

	public Byte getStatus() {
		return status;
	}

	public void setStatus(Byte status) {
		this.status = status;
	}

//	public Byte getEventType() {
//		return eventType;
//	}
//
//	public void setEventType(Byte responseType) {
//		this.eventType = responseType;
//	}

}
