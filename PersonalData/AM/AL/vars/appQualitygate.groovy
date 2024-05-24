import com.eb.lib.aosp.aospUtils
import com.eb.lib.aosp.CommonEnvironment
import groovy.json.JsonBuilder
def call(body) {
  // For pipeline common config
  def commonlib = new aospUtils()
  def config = body.script.config
  def configRuntime = body.script.configRuntime
  if (currentBuild.result == "SUCCESS") {
    node(configRuntime.leastloadednode) {
      timestamps {
        def launchersDir = "${configRuntime.workspace}/.launchers"
        if (!fileExists(launchersDir)) {
          dir "${configRuntime.workspace}/.launchers", {
            git branch: "${body.script.buildtools_branch}",
                    url: "${body.script.buildtools_url}"
          }
        } else {
            echo "Directory ${configRuntime.workspace}/.launchers already exists."
        }
      }
      try {
        dir "${configRuntime.app_workspace}", {
            echo "node name:" + configRuntime.leastloadednode
            echo "Sonarqube Quality gate check and publish the result : ${GERRIT_PROJECT}"
            withSonarQubeEnv('SonarServer') {
                if (configRuntime.pipelineType.contains("verify")) {
                    timeout(time: 1, unit: 'HOURS') {
                    def qg = waitForQualityGate() 
                        if (qg.status != 'OK') {
                        error "Pipeline aborted due to quality gate failure: ${qg.status}"
                        }
                    }
                }
            }
        }
      } catch(err) {
        commonlib.__INFO(err.toString())
        currentBuild.result = 'FAILURE'
        configRuntime.BUILD_STATUS = 'FAILURE'
        configRuntime.stage_results[STAGE_NAME] = "FAILURE"
        configRuntime.build_addinfo = "(Quality Gate Failed)!!!"
        configRuntime.failure_mail_body = String.format(configRuntime.failure_mail_body.toString(),err.toString()+configRuntime.build_addinfo+"%s",configRuntime.email_build_info)
      }
    }
  }
}