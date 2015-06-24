/*
 * Copyright 2011-2012 the original author or authors.
 * 
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * 
 *      http://www.apache.org/licenses/LICENSE-2.0
 * 
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.springframework.shell;

import java.io.IOException;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import org.springframework.beans.factory.support.RootBeanDefinition;
import org.springframework.beans.factory.xml.XmlBeanDefinitionReader;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.ClassPathBeanDefinitionScanner;
import org.springframework.context.support.GenericApplicationContext;
import org.springframework.shell.core.ExitShellRequest;
import org.springframework.shell.core.JLineShellComponent;
import org.springframework.shell.core.Shell;
import org.springframework.shell.support.logging.HandlerUtils;
import org.springframework.util.StopWatch;

/**
 * Loads a {@link Shell} using Spring IoC container.
 * 
 * @author Ben Alex (original Roo code)
 * @author Mark Pollack
 * @author David Winterfeldt
 * 
 */
public class Bootstrap {

	private final static String[] CONTEXT_PATH = { "classpath*:/META-INF/spring/spring-shell-plugin.xml" };

	private static Bootstrap bootstrap;

	private static StopWatch sw = new StopWatch("Spring Shell");

	private static CommandLine commandLine;

	private GenericApplicationContext ctx;

	public static void main(String[] args) throws IOException {
		sw.start();
		ExitShellRequest exitShellRequest;
		try {
			bootstrap = new Bootstrap(args);
			exitShellRequest = bootstrap.run();
		}
		catch (RuntimeException t) {
			throw t;
		}
		finally {
			HandlerUtils.flushAllHandlers(Logger.getLogger(""));
		}

		sw.stop();
		if (bootstrap.getJLineShellComponent().isDevelopmentMode()) {
			System.out.println("Total execution time: " + sw.getLastTaskTimeMillis() + " ms");
		}

		System.exit(exitShellRequest.getExitCode());
	}

	public Bootstrap() {
		this(null, CONTEXT_PATH);
	}

	public Bootstrap(String[] args) throws IOException {
		this(args, CONTEXT_PATH);
	}

	public Bootstrap(String[] args, String[] contextPath) {
		try {
			commandLine = SimpleShellCommandLineOptions.parseCommandLine(args);
		}
		catch (IOException e) {
			throw new ShellException(e.getMessage(), e);
		}

		ctx = new GenericApplicationContext();
		ctx.registerShutdownHook();
		configureApplicationContext(ctx);
		// built-in commands and converters
		ClassPathBeanDefinitionScanner scanner = new ClassPathBeanDefinitionScanner(ctx);
		if (commandLine.getDisableInternalCommands()) {
			scanner.scan("org.springframework.shell.converters", "org.springframework.shell.plugin.support");
		}
		else {
			scanner.scan("org.springframework.shell.commands", "org.springframework.shell.converters",
					"org.springframework.shell.plugin.support");
		}
		// user contributed commands
		XmlBeanDefinitionReader reader = new XmlBeanDefinitionReader(ctx);
		reader.loadBeanDefinitions(contextPath);
		ctx.refresh();
	}

  public Bootstrap(String[] args, GenericApplicationContext givenCtx) {
    try {
      commandLine = SimpleShellCommandLineOptions.parseCommandLine(args);
    } catch (IOException e) {
      throw new ShellException(e.getMessage(), e);
    }

    this.ctx = givenCtx;
    this.ctx.registerShutdownHook();
    configureApplicationContext(this.ctx);

    ClassPathBeanDefinitionScanner scanner = new ClassPathBeanDefinitionScanner(this.ctx);
    if (commandLine.getDisableInternalCommands())
      scanner.scan(new String[] { "org.springframework.shell.converters", "org.springframework.shell.plugin.support" });
    else
      scanner.scan(new String[] { "org.springframework.shell.commands", "org.springframework.shell.converters", "org.springframework.shell.plugin.support" });
  }
  
	public ApplicationContext getApplicationContext() {
		return ctx;
	}

	private void configureApplicationContext(GenericApplicationContext annctx) {
		createAndRegisterBeanDefinition(annctx, org.springframework.shell.core.JLineShellComponent.class, "shell");
		annctx.getBeanFactory().registerSingleton("commandLine", commandLine);
	}

	protected void createAndRegisterBeanDefinition(GenericApplicationContext annctx, Class<?> clazz) {
		createAndRegisterBeanDefinition(annctx, clazz, null);
	}

	protected void createAndRegisterBeanDefinition(GenericApplicationContext annctx, Class<?> clazz, String name) {
		RootBeanDefinition rbd = new RootBeanDefinition();
		rbd.setBeanClass(clazz);
		DefaultListableBeanFactory bf = (DefaultListableBeanFactory) annctx.getBeanFactory();
		if (name != null) {
			bf.registerBeanDefinition(name, rbd);
		}
		else {
			bf.registerBeanDefinition(clazz.getSimpleName(), rbd);
		}
	}

	// seems on JDK 1.6.0_18 or higher causes the output to disappear
	private void setupLogging() {
		// Ensure all JDK log messages are deferred until a target is registered
		Logger rootLogger = Logger.getLogger("");
		HandlerUtils.wrapWithDeferredLogHandler(rootLogger, Level.SEVERE);

		// Set a suitable priority level on Spring Framework log messages
		Logger sfwLogger = Logger.getLogger("org.springframework");
		sfwLogger.setLevel(Level.WARNING);

		// Set a suitable priority level on Roo log messages
		// (see ROO-539 and HandlerUtils.getLogger(Class))
		Logger rooLogger = Logger.getLogger("org.springframework.shell");
		rooLogger.setLevel(Level.FINE);
	}

	public ExitShellRequest run() {

		String[] commandsToExecuteAndThenQuit = commandLine.getShellCommandsToExecute();
		// The shell is used
		JLineShellComponent shell = ctx.getBean("shell", JLineShellComponent.class);
		ExitShellRequest exitShellRequest;

		if (null != commandsToExecuteAndThenQuit) {
			boolean successful = false;
			exitShellRequest = ExitShellRequest.FATAL_EXIT;

			for (String cmd : commandsToExecuteAndThenQuit) {
				successful = shell.executeCommand(cmd).isSuccess();
				if (!successful)
					break;
			}

			// if all commands were successful, set the normal exit status
			if (successful) {
				exitShellRequest = ExitShellRequest.NORMAL_EXIT;
			}
		}
		else {
			shell.start();
			shell.promptLoop();
			exitShellRequest = shell.getExitShellRequest();
			if (exitShellRequest == null) {
				// shouldn't really happen, but we'll fallback to this anyway
				exitShellRequest = ExitShellRequest.NORMAL_EXIT;
			}
			shell.waitForComplete();
		}

		ctx.close();
		return exitShellRequest;
	}

	public JLineShellComponent getJLineShellComponent() {
		return ctx.getBean("shell", JLineShellComponent.class);
	}
}