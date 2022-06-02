
package cn.spdb.harrier.common.log;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.filter.Filter;
import ch.qos.logback.core.spi.FilterReply;

public class MasterLogFilter extends Filter<ILoggingEvent> {

	private Level level = Level.INFO;

	public void setLevel(String level) {
		this.level = Level.toLevel(level);
	}

	@Override
	public FilterReply decide(ILoggingEvent event) {
		if ((event.getThreadName().startsWith("Master") || event.getLoggerName().startsWith("Master"))
				&& event.getLevel().isGreaterOrEqual(level)) {
			return FilterReply.ACCEPT;
		}
		return FilterReply.DENY;
	}

}