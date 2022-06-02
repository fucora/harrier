package cn.spdb.harrier.api.service.system;

import java.util.Set;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.stereotype.Service;
import org.springframework.util.CollectionUtils;

import cn.spdb.harrier.api.model.LoginUser;
import cn.spdb.harrier.api.utils.SecurityUtils;
import cn.spdb.harrier.common.uitls.StringUtils;
import cn.spdb.harrier.common.uitls.Symbol;

/**
 * 	@PreAuthorize("@HarrierPermission.hasPermissions(#platfrom,*,'R')")
 * @author luzl2
 *
 */
@Service("HarrierPermission")
public class SysPermissionService {

	public static final String SUPER_ADMIN = "admin";

	public boolean hasPermissions(String platfrom, String system, String permission) {
		if(SecurityUtils.isAdmin()) {
			return true;
		}
		if (StringUtils.isBlank(platfrom) || StringUtils.isBlank(system) || StringUtils.isBlank(permission)) {
			return false;
		}
		LoginUser loginUser = SecurityUtils.getLoginUser();
		if (null == loginUser || CollectionUtils.isEmpty(loginUser.getPermissions())) {
			return false;
		}
		StringBuilder sb = new StringBuilder().append(platfrom).append(Symbol.MAO_HAO).append(Symbol.XING_HAO)
				.append(Symbol.MAO_HAO).append(permission);
		boolean flage = hasPermissions(loginUser.getPermissions(), sb.toString());
		if (flage) {
			return flage;
		}
		sb.setLength(0);
		sb.append(platfrom).append(Symbol.MAO_HAO).append(system).append(Symbol.MAO_HAO).append(permission);
		flage = hasPermissions(loginUser.getPermissions(), sb.toString());
		return flage;
	}

	private boolean hasPermissions(Set<String> permissions, String permission) {
		return permissions.contains(permission.trim());
	}

	public boolean hasPermissionsRole(String role) {
		if (StringUtils.isBlank(role)) {
			return false;
		}
		LoginUser loginUser = SecurityUtils.getLoginUser();
		if (null == loginUser || CollectionUtils.isEmpty(loginUser.getAuthorities())) {
			return false;
		}

		if(SecurityUtils.isAdmin()) {
			return true;
		}
		return false;

	}

	/**
	 * 
	 * @param roles , 分割
	 * @return
	 */
	public boolean hasAnyRoles(String roles) {
		if (StringUtils.isBlank(roles)) {
			return false;
		}
		LoginUser loginUser = SecurityUtils.getLoginUser();
		if (null == loginUser || CollectionUtils.isEmpty(loginUser.getAuthorities())) {
			return false;
		}
		if(SecurityUtils.isAdmin()) {
			return true;
		}
		for (String role : roles.split(Symbol.DOU_HAO)) {
			for (GrantedAuthority ga : loginUser.getAuthorities()) {
				if (ga.getAuthority().equals(role)) {
					return true;
				}
			}
		}
		return false;
	}
}
