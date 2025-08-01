package com.browserstack.automate.ci.jenkins.integrationService;

import com.browserstack.automate.ci.common.constants.Constants;
import com.browserstack.automate.ci.jenkins.BrowserStackCredentials;
import com.google.gson.Gson;
import hudson.EnvVars;
import hudson.FilePath;
import hudson.model.Action;
import hudson.model.Run;
import hudson.model.TaskListener;
import hudson.tasks.ArtifactArchiver;
import org.json.JSONObject;
import okhttp3.*;

import java.io.IOException;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;

public class BrowserStackTestReportAction implements Action {

  private static final String DEFAULT_REPORT_TIMEOUT = "120";
  private static final String SUCCESS_REPORT = "SUCCESS_REPORT";
  private static final String REPORT_IN_PROGRESS = "REPORT_IN_PROGRESS";
  private static final String REPORT_FAILED = "REPORT_FAILED";
  private static final String RETRY_REPORT = "RETRY_REPORT";
  private static final String RATE_LIMIT = "RATE_LIMIT";
  private static final String TEST_AVAILABLE = "TEST_AVAILABLE";
  private static final int MAX_ATTEMPTS = 3;
  private final RequestsUtil requestsUtil;
  private final BrowserStackCredentials credentials;
  private final String buildName;
  private final String buildCreatedAt;

  private Run<?, ?> run;
  private String reportHtml;
  private String reportStyle;
  private String reportStatus;
  private int maxRetryReportAttempt;

  public BrowserStackTestReportAction(Run<?, ?> run, BrowserStackCredentials credentials, String buildName, String buildCreatedAt) {
    this.run = run;
    this.credentials = credentials;
    this.buildName = buildName;
    this.buildCreatedAt = buildCreatedAt;
    this.requestsUtil = new RequestsUtil();
    this.reportHtml = null;
    this.reportStyle = "";
    this.reportStatus = "";
    this.maxRetryReportAttempt = MAX_ATTEMPTS;
  }

  public String getReportHtml() {
    ensureReportFetched();
    return reportHtml;
  }

  public String getReportStyle() {
    ensureReportFetched();
    return reportStyle;
  }

  private void ensureReportFetched() {
    if (!isReportCompletedOrFailed()) {
      fetchReport();
    }
  }

  private boolean isReportCompletedOrFailed() {
    return reportStatus.equals(SUCCESS_REPORT) || reportStatus.equals(REPORT_FAILED);
  }

  private void fetchReport() {
    Map<String, Object> params = createReportParams();
    String reportUrl = Constants.CAD_BASE_URL + Constants.BROWSERSTACK_CONFIG_DETAILS_ENDPOINT;

    try {
      Response response = requestsUtil.makeRequest(reportUrl, credentials, createRequestBody(params));
      handleResponse(response);
    } catch (Exception e) {
      if(!isReportTestAvailable()) {
        handleFetchException(e);
      }
    }
  }

  private Map<String, Object> createReportParams() {
    String RquestTypeForJenkins = "POLL";
    Map<String, Object> params = new HashMap<>();
    params.put("buildStartedAt", buildCreatedAt);
    params.put("originalBuildName", buildName);
    params.put("requestingCi", Constants.INTEGRATIONS_TOOL_KEY);
    params.put("reportFormat", Arrays.asList("richHtml", "basicHtml"));
    params.put("requestType", RquestTypeForJenkins);
    params.put("userTimeout", DEFAULT_REPORT_TIMEOUT);
    return params;
  }

  private RequestBody createRequestBody(Map<String, Object> params) {
    Gson gson = new Gson();
    String json = gson.toJson(params);
    return RequestBody.create(MediaType.parse("application/json"), json);
  }

  private void handleResponse(Response response) throws Exception {
    if (response.isSuccessful()) {
      processSuccessfulResponse(response);
    } else {
      if(!isReportTestAvailable()) {
        if (response.code() == 429) {
          reportStatus = RATE_LIMIT;
        } else {
          reportStatus = REPORT_FAILED;
        }
      }
    }
  }

  private void processSuccessfulResponse(Response response) throws Exception {
    assert response.body() != null;
    JSONObject reportResponse = new JSONObject(response.body().string());
    String responseReportStatus = reportResponse.optString("reportStatus");
    JSONObject report = reportResponse.optJSONObject("report");

    switch (responseReportStatus.toUpperCase()) {
      case "COMPLETED":
      case "NOT_AVAILABLE":
        setReportSuccess(report);
        break;
      case "IN_PROGRESS":
        reportStatus = REPORT_IN_PROGRESS;
        break;
      case "TEST_AVAILABLE":
        setReportSuccess(report);
        reportStatus = TEST_AVAILABLE;
        break;
      default:
        reportStatus = REPORT_FAILED;
    }
  }

  private void setReportSuccess(JSONObject report) {
    String defaultHTML = "<h1>No Report Found</h1>";
    reportStatus = SUCCESS_REPORT;
    reportHtml = report != null ? report.optString("richHtml", defaultHTML) : defaultHTML;
    reportStyle = report != null ? report.optString("richCss", "") : "";

    try {
      String basicHtml = report != null ? report.optString("basicHtml", defaultHTML) : defaultHTML;
      String fullHtml = "<!DOCTYPE html> <html><head> <head>" + basicHtml + "</html>";

      // Save the HTML content to a file in the workspace
      FilePath workspace = new FilePath(run.getRootDir()).getParent();
      ArtifactArchiver artifactArchiver = getArtifactArchiver(workspace, fullHtml);
      artifactArchiver.perform(run, workspace, new EnvVars(), null, TaskListener.NULL);
    } catch (Exception e) {
      //do nothing, as we don't want to fail the build if archiving fails
    }
  }

  private static ArtifactArchiver getArtifactArchiver(FilePath workspace, String fullHtml) throws IOException, InterruptedException {
    FilePath artifactsDir = new FilePath(workspace, Constants.BROWSERSTACK_REPORT_FOLDER);
    artifactsDir.mkdirs();
    String htmlFileName = Constants.BROWSERSTACK_REPORT_FILENAME + ".html";

    FilePath htmlFile = new FilePath(artifactsDir, htmlFileName);

    htmlFile.write(fullHtml, "UTF-8");
    String artifactFilePath = Constants.BROWSERSTACK_REPORT_FOLDER + "/" + htmlFileName;
    // Archive the file as an artifact
    ArtifactArchiver artifactArchiver = new ArtifactArchiver(artifactFilePath);
    artifactArchiver.setAllowEmptyArchive(false);
    return artifactArchiver;
  }

  private void handleFetchException(Exception e) {
    reportStatus = RETRY_REPORT;
    maxRetryReportAttempt--;
    if (maxRetryReportAttempt < 0) {
      reportStatus = REPORT_FAILED;
    }
  }

  public boolean isReportInProgress() {
    return reportStatus.equals(REPORT_IN_PROGRESS);
  }

  public boolean isReportFailed() {
    return reportStatus.equals(REPORT_FAILED);
  }

  public boolean reportRetryRequired() {
    return reportStatus.equals(RETRY_REPORT);
  }

  public boolean isUserRateLimited() {
    return reportStatus.equals(RATE_LIMIT);
  }

  public boolean isReportAvailable() {
    return reportStatus.equals(SUCCESS_REPORT) || reportStatus.equals(TEST_AVAILABLE);
  }

  public boolean isReportTestAvailable() {
    return reportStatus.equals(TEST_AVAILABLE);
  }

  public boolean reportHasStatus() {
    return !reportStatus.isEmpty() && (reportStatus.equals(REPORT_IN_PROGRESS) || reportStatus.equals(REPORT_FAILED));
  }

  public Run<?, ?> getBuild() {
    return run;
  }

  public void setBuild(Run<?, ?> build) {
    this.run = build;
  }

  @Override
  public String getIconFileName() {
    return Constants.BROWSERSTACK_LOGO;
  }

  @Override
  public String getDisplayName() {
    return Constants.BROWSERSTACK_REPORT_DISPLAY_NAME;
  }

  @Override
  public String getUrlName() {
    return Constants.BROWSERSTACK_TEST_REPORT_URL;
  }
}