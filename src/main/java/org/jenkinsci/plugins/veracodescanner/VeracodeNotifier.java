/*
 * Copyright (c) 2011 Rackspace Hosting
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy of this software and associated documentation files (the "Software"), to deal in the Software without restriction, including without limitation the rights to use, copy, modify, merge, publish, distribute, sublicense, and/or sell copies of the Software, and to permit persons to whom the Software is furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in all copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY, FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM, OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE SOFTWARE.
 */

package org.jenkinsci.plugins.veracodescanner;

import hudson.Extension;
import hudson.FilePath;
import hudson.Launcher;
import hudson.model.BuildListener;
import hudson.model.AbstractBuild;
import hudson.model.AbstractProject;
import hudson.model.Cause;
import hudson.model.Result;
import hudson.remoting.VirtualChannel;
import hudson.tasks.BuildStepDescriptor;
import hudson.tasks.BuildStepMonitor;
import hudson.tasks.Notifier;
import hudson.tasks.Publisher;
import hudson.util.FormValidation;
import hudson.EnvVars;

import java.io.File;
import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringReader;
import java.io.StringWriter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

import javax.xml.bind.JAXBContext;
import javax.xml.bind.JAXBException;
import javax.xml.bind.Unmarshaller;

import net.sf.json.JSONObject;

import org.jenkinsci.plugins.veracodescanner.exception.VeracodeScannerException;
import org.jenkinsci.plugins.veracodescanner.model.AppType;
import org.jenkinsci.plugins.veracodescanner.model.Applist;
import org.jenkinsci.plugins.veracodescanner.model.BuildTriggers;
import org.jenkinsci.plugins.veracodescanner.model.buildinfo.AnalysisUnitType;
import org.jenkinsci.plugins.veracodescanner.model.buildinfo.Buildinfo;
import org.jenkinsci.plugins.veracodescanner.model.prescan.ModuleType;
import org.jenkinsci.plugins.veracodescanner.model.prescan.Prescanresults;
import org.kohsuke.stapler.DataBoundConstructor;
import org.kohsuke.stapler.QueryParameter;
import org.kohsuke.stapler.StaplerRequest;

import com.veracode.apiwrapper.wrappers.UploadAPIWrapper;

public class VeracodeNotifier extends Notifier {

	private final String includes;
	private final String applicationName;
	private final int scanFrequency;
	private final int prescanTimeout;
	private final String platformName;
	private final String scanName;
	private final BuildTriggers triggers;

	@DataBoundConstructor
	public VeracodeNotifier(String includes, String applicationName, int scanFrequency, int prescanTimeout, String platformName, String scanName, BuildTriggers triggers) {
		this.includes = includes;
		this.applicationName = applicationName;
		this.scanFrequency = scanFrequency;
		this.prescanTimeout = prescanTimeout;
		this.platformName = platformName;
		this.scanName = scanName;

		this.triggers = triggers;
	}

	@Override
	public boolean needsToRunAfterFinalized() {
		return true;
	}

	public BuildStepMonitor getRequiredMonitorService() {
		return BuildStepMonitor.BUILD;
	}

	@Override
	public boolean perform(AbstractBuild<?, ?> build, Launcher launcher, BuildListener listener) throws InterruptedException, IOException {
		try {
			if (triggers == null) {
				performScan(build, listener);
			} else {
				List<Cause> causes = build.getCauses();
				for (Cause cause : causes) {
					if (triggers.isTriggeredBy(cause.getClass())) {
						performScan(build, listener);
						break;
					}
				}
			}
		} catch (VeracodeScannerException e) {
			listener.getLogger().println(e.getMessage());
			listener.fatalError(e.getMessage());
			StringWriter sw = new StringWriter();
			PrintWriter pw = new PrintWriter(sw);
			e.printStackTrace(pw);
			listener.getLogger().println(sw.toString());

			if (this.getDescriptor().getFails()) {
				return false;
			}
		}
		return true;
	}

	public String getIncludes() {
		return includes;
	}

	public String getApplicationName() {
		return applicationName;
	}

	public String getScanFrequency() {
		return Integer.toString(scanFrequency);
	}

	public String getPrescanTimeout() {
		return Integer.toString(prescanTimeout);
	}

	public String getPlatformName() {
		return platformName;
	}

	public String getScanName() {
		return scanName;
	}

	public boolean isOverrideTriggers() {
		if (triggers != null) {
			return triggers.isTriggerManually() || triggers.isTriggerPeriodically() || triggers.isTriggerScm();
		} else {
			return false;
		}
	}

	public BuildTriggers getTriggers() {
		return triggers;
	}

	private void performScan(AbstractBuild<?, ?> build, BuildListener listener) throws IOException, InterruptedException, VeracodeScannerException {
		try {
			FilePath workspace = build.getWorkspace();
			EnvVars envVars = build.getEnvironment(listener);

			UploadAPIWrapper veracodeUploadClient = new UploadAPIWrapper();
			veracodeUploadClient.setUpCredentials(getDescriptor().getVeracodeUser(), getDescriptor().getVeracodePass());

			String appName = envVars.expand(applicationName);
			String appId = getAppId(veracodeUploadClient, appName, listener);

			if (appId != null) {
				if (isScanNeeded(veracodeUploadClient, appId, listener)) {
					if (scanName.length()>0) {
						String customScanName = envVars.expand(scanName);
						listener.getLogger().println("Creating Veracode scan: " + customScanName);
						veracodeUploadClient.createBuild(appId, customScanName);
					}

					if (workspace.isRemote()) {
						if (this.getDescriptor().getVerbose()) {
							listener.getLogger().println("Remote workspace detected.");
						}

						if (this.getDescriptor().getRemote()) {
							FilePath localWorkspace = new FilePath(build.getParent().getRootDir());

							if (this.getDescriptor().getVerbose()) {
								listener.getLogger().println("Preparing workspace for file upload");
							}

							workspace = prepareLocalWorkspace(localWorkspace.withSuffix("/workspace-remote"), workspace);

							if (this.getDescriptor().getVerbose()) {
								listener.getLogger().println("Files copied into " + workspace.getRemote());
							}

						} else {
							listener.getLogger().println("[WARNING] Remote workspace detected. Please consider enabling Master/slave mode in the plugin settings");
						}
					}

					FilePath[] filesToScan = workspace.list(includes);
					listener.getLogger().println("Uploading Files to Veracode: " + Arrays.toString(filesToScan));
					listener.getLogger().println("Veracode User: " + getDescriptor().getVeracodeUser());

					List<File> filesToUpload = convertFilePaths(filesToScan);
					for (File file : filesToUpload) {
						veracodeUploadClient.uploadFile(appId, file.getAbsolutePath());
					}

					if (!filesToUpload.isEmpty()) {
						Prescanresults prescanResult = executePreScan(veracodeUploadClient, appId, listener);
						executeScan(veracodeUploadClient, appId, listener, prescanResult);
					}

					listener.getLogger().println("Veracode Scan Succeeded.  Email will be sent once results are ready.");
				} else {
					listener.getLogger().println("Veracode scan is not needed at this time.");
				}
			} else {
				throw new VeracodeScannerException("Failed to get application id for app " + appName);
			}

		} catch (IOException e) {
			throw new VeracodeScannerException("Veracode scan failed.", e);
		} catch (InterruptedException ie) {
			throw new VeracodeScannerException("Reading files to scan failed.", ie);
		}

	}

	private FilePath prepareLocalWorkspace(FilePath localWorkspace, FilePath remoteWorkspace) throws VeracodeScannerException, InterruptedException {
		try {

			if (localWorkspace.exists()) {
				localWorkspace.deleteContents();
			} else {
				localWorkspace.mkdirs();
			}

			remoteWorkspace.copyRecursiveTo(includes, localWorkspace);

			return localWorkspace;
		} catch (IOException e) {
			throw new VeracodeScannerException("Could not prepare local workspace", e);
		}
	}

	private boolean isScanNeeded(UploadAPIWrapper veracodeUploadClient, String appId, BuildListener listener) throws VeracodeScannerException {
		boolean scanNeeded = false;
		try {
			JAXBContext jaxbContext = JAXBContext.newInstance(Buildinfo.class);
			Unmarshaller jaxbUnmarshaller = jaxbContext.createUnmarshaller();

			Buildinfo buildInfo = (Buildinfo) jaxbUnmarshaller.unmarshal(new StringReader(veracodeUploadClient.getBuildInfo(appId)));

			if (buildInfo.getBuild().isResultsReady()) {
				// There could be multiple analysis units per build, but we'll just grab the first one and use it
				if (buildInfo.getBuild().getAnalysisUnit().isEmpty()) {
					// default to true if the last build didn't include any analysis units
					listener.getLogger().println("No analysis units are present in the last build, so another scan is going to be initiated.");
					scanNeeded = true;
				} else {
					AnalysisUnitType analysisUnit = buildInfo.getBuild().getAnalysisUnit().get(0);
					long lastScan = analysisUnit.getPublishedDate().toGregorianCalendar().getTimeInMillis();
					long timeSinceLastScan = System.currentTimeMillis() - lastScan;
					long scanFrequencyInMillis = Long.valueOf(scanFrequency) * 24l * 60l * 60l * 1000l;
					if (timeSinceLastScan > scanFrequencyInMillis) {
						scanNeeded = true;
					}
				}
			} else {
				listener.getLogger().println("Last scan is still in progress, so do not initiate a new scan.");
			}
		} catch (IOException e) {
			throw new VeracodeScannerException("Unable to read scanning frequency file.", e);
		} catch (JAXBException e) {
			listener.getLogger().println("Failed to get build info to determine if scan is needed.  We'll assume one is needed.");
			scanNeeded = true;
		}
		return scanNeeded;
	}

	private String getAppId(UploadAPIWrapper veracodeUploadClient, String applicationName, BuildListener listener) throws VeracodeScannerException {
		String appId = null;
		try {
			String appListXml = veracodeUploadClient.getAppList();
			JAXBContext jaxbContext = JAXBContext.newInstance(Applist.class);
			Unmarshaller jaxbUnmarshaller = jaxbContext.createUnmarshaller();
			Applist appList = (Applist) jaxbUnmarshaller.unmarshal(new StringReader(appListXml));

			for (AppType app : appList.getApp()) {
				if (app.getAppName().equalsIgnoreCase(applicationName)) {
					appId = app.getAppId().toString();
					break;
				}
			}
			if (appId == null) {
				listener.getLogger().println("App with name " + applicationName + " was not found.  List of apps available below: " + appListXml);
			}
		} catch (Exception e) {
			listener.getLogger().println(e.getMessage());
			throw new VeracodeScannerException(e);
		}

		return appId;
	}

	private Prescanresults executePreScan(UploadAPIWrapper veracodeUploadClient, String appId, BuildListener listener) throws VeracodeScannerException {
		listener.getLogger().println("Starting execution of prescan.");
		Prescanresults results = null;
		try {
			veracodeUploadClient.beginPreScan(appId);
			JAXBContext jaxbContext = JAXBContext.newInstance(Prescanresults.class);
			Unmarshaller jaxbUnmarshaller = jaxbContext.createUnmarshaller();
			int attemptsLeft = prescanTimeout;
			while (attemptsLeft > 0) {
				String preScanResultsXml = veracodeUploadClient.getPreScanResults(appId);
				if (getDescriptor().getVerbose()) {
					listener.getLogger().println(preScanResultsXml);
				}
				listener.getLogger().println("Attempts Left: " + attemptsLeft);
				try {
					results = (Prescanresults) jaxbUnmarshaller.unmarshal(new StringReader(preScanResultsXml));
				} catch (JAXBException je) {
					// Results not available yet, just ignore this exception for now.
				}
				if (results == null) {
					attemptsLeft--;
					// Wait 60 seconds and try again
					Thread.sleep(60000);
				} else {
					break;
				}
			}
		} catch (Exception e) {
			listener.getLogger().println("Failed to get pre scan results. " + e.getMessage());
			throw new VeracodeScannerException(e);
		}
		if (results == null) {
			throw new VeracodeScannerException("Unable to get prescan results");
		}
		listener.getLogger().println("Prescan is finished.");
		return results;
	}

	private void executeScan(UploadAPIWrapper veracodeUploadClient, String appId, BuildListener listener, Prescanresults resultsOfPrescan)
			throws VeracodeScannerException {
		listener.getLogger().println("Starting execution of scan.");
		try {
			String moduleId = null;
			String platform = null;
			String scanAllModules = "true";

			for (ModuleType module : resultsOfPrescan.getModule()) {
				if (platformName.length()>0) {
					platform = module.getPlatform();

					if (platform.contains(platformName)) {
						moduleId = module.getId().toString();

						if (moduleId.length()>0) {
							scanAllModules = "false";
							break;
						}
					}
				}

				if (module.isHasFatalErrors()) {
					throw new VeracodeScannerException("Prescan failed for some modules.  Check prescan results.");
				}
			}

			if (moduleId != null) {
				listener.getLogger().println("Selected module id: " + moduleId + " (" + platform + ")");
			}

			String buildInfoXml = veracodeUploadClient.beginScan(appId, moduleId, scanAllModules);

			if (getDescriptor().getVerbose()) {
				listener.getLogger().println(buildInfoXml);
			}
		} catch (Exception e) {
			throw new VeracodeScannerException(e);
		}
		listener.getLogger().println("Scan has been started.");
	}

	private List<File> convertFilePaths(FilePath[] filePaths) throws VeracodeScannerException {
		List<File> files = new ArrayList<File>();

		for (FilePath path : filePaths) {
			files.add(getFile(path));
		}

		return files;
	}

	private File getFile(FilePath path) throws VeracodeScannerException {
		File file;

		try {
			file = path.act(new FileGetter());
		} catch (IOException e) {
			throw new VeracodeScannerException("Could not load the build artifact to send.", e);
		} catch (InterruptedException e) {
			throw new VeracodeScannerException("The file operation was interrupted.", e);
		}

		return file;
	}

	@Override
	public DescriptorImpl getDescriptor() {
		return (DescriptorImpl) super.getDescriptor();
	}

	@Extension
	public static class DescriptorImpl extends BuildStepDescriptor<Publisher> {

		private String veracodeUser;
		private String veracodePass;
		private String defaultScanFrequency;
		private Boolean verbose;
		private Boolean remote;
		private Boolean fails;
		private String defaultPrescanTimeout;

		public DescriptorImpl() {
			super(VeracodeNotifier.class);
			load();
		}

		@Override
		public String getDisplayName() {
			return "Submit Artifacts For Veracode Scan";
		}

		@Override
		public boolean isApplicable(Class<? extends AbstractProject> item) {
			return true;
		}

		@Override
		public boolean configure(StaplerRequest req, JSONObject o) throws FormException {

			// to persist global configuration information,
			// set that to properties and call save().
			veracodeUser = o.getString("veracode_user");
			veracodePass = o.getString("veracode_pass");
			defaultPrescanTimeout = o.getString("defaultPrescanTimeout");
			defaultScanFrequency = o.getString("defaultScanFrequency");
			verbose = o.getBoolean("verbose");
			remote = o.getBoolean("remote");
			fails = o.getBoolean("fails");
			save();
			return super.configure(req, o);
		}

		public String getVeracodeUser() {
			return veracodeUser;
		}

		public String getVeracodePass() {
			return veracodePass;
		}

		public String getDefaultScanFrequency() {
			return defaultScanFrequency;
		}

		public void setDefaultScanFrequency(String defaultScanFrequency) {
			this.defaultScanFrequency = defaultScanFrequency;
		}

		public String getDefaultPrescanTimeout() {
			return defaultPrescanTimeout;
		}

		public void setDefaultPrescanTimeout(String defaultPrescanTimeout) {
			this.defaultPrescanTimeout = defaultPrescanTimeout;
		}

		public Boolean getVerbose() {
			return verbose;
		}

		public void setVerbose(Boolean verbose) {
			this.verbose = verbose;
		}

		public Boolean getRemote() {
			return remote;
		}

		public void setRemote(Boolean remote) {
			this.remote = remote;
		}

		public Boolean getFails() {
			return fails;
		}

		public void setFails(Boolean fails) {
			this.fails = fails;
		}

		public FormValidation doCheckScanFrequency(@QueryParameter String scanFrequency) {
			try {
				Long.parseLong(scanFrequency);
				return FormValidation.ok();
			} catch (NumberFormatException e) {
				return FormValidation.error("Not a valid value for scan frequency. Please specify an integer.");
			}
		}

		public FormValidation doCheckPrescanTimeout(@QueryParameter String prescanTimeout) {
			try {
				Long.parseLong(prescanTimeout);
				return FormValidation.ok();
			} catch (NumberFormatException e) {
				return FormValidation.error("Not a valid value for prescan timeout. Please specify an integer.");
			}
		}

		public FormValidation doCheckDefaultScanFrequency(@QueryParameter String defaultScanFrequency) {
			try {
				Long.parseLong(defaultScanFrequency);
				return FormValidation.ok();
			} catch (NumberFormatException e) {
				return FormValidation.error("Not a valid value for scan frequency. Please specify an integer.");
			}
		}

		public FormValidation doCheckDefaultPrescanTimeout(@QueryParameter String defaultPrescanTimeout) {
			try {
				Long.parseLong(defaultPrescanTimeout);
				return FormValidation.ok();
			} catch (NumberFormatException e) {
				return FormValidation.error("Not a valid value for prescan timeout. Please specify an integer.");
			}
		}
	}

	private static class FileGetter implements FilePath.FileCallable<File> {
		public File invoke(File f, VirtualChannel channel) {
			return f;
		}
	}
}
