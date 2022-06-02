package cn.spdb.harrier.rpc.transport.File;

public class FileAtt {

	private String path;
	private Long size;
	private Boolean dir;
	private String user;
	private String group;
	private String authority;
	private Boolean exist;
	private Long creatTime;
	private Long lastModTime;

	public String getPath() {
		return path;
	}

	public void setPath(String path) {
		this.path = path;
	}

	public Long getSize() {
		return size;
	}

	public Long getCreatTime() {
		return creatTime;
	}

	public void setCreatTime(Long creatTime) {
		this.creatTime = creatTime;
	}

	public Long getLastModTime() {
		return lastModTime;
	}

	public void setLastModTime(Long lastModTime) {
		this.lastModTime = lastModTime;
	}

	public void setSize(Long size) {
		this.size = size;
	}

	public Boolean getDir() {
		return dir;
	}

	public void setDir(Boolean dir) {
		this.dir = dir;
	}

	public String getUser() {
		return user;
	}

	public void setUser(String user) {
		this.user = user;
	}

	public String getGroup() {
		return group;
	}

	public void setGroup(String group) {
		this.group = group;
	}

	public String getAuthority() {
		return authority;
	}

	public void setAuthority(String authority) {
		this.authority = authority;
	}

	public Boolean getExist() {
		return exist;
	}

	public void setExist(Boolean exist) {
		this.exist = exist;
	}

	public FileAtt(String path) {
		super();
		this.path = path;
	}

}
