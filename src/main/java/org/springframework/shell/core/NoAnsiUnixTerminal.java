package org.springframework.shell.core;

import jline.Terminal;
import jline.UnixTerminal;

public class NoAnsiUnixTerminal extends UnixTerminal implements Terminal {

	public NoAnsiUnixTerminal() throws Exception {
		super();
		setAnsiSupported(false);
	}

}
