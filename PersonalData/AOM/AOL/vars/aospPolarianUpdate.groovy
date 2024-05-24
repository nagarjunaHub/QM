import com.eb.lib.aosp.aospUtils
import com.eb.lib.aosp.CommonEnvironment
import groovy.json.JsonBuilder
import org.jenkinsci.plugins.workflow.steps.FlowInterruptedException

def call(body) {
// For pipeline common config
  def commonlib = new aospUtils()
  def config = body.script.config
  def configRuntime = body.script.configRuntime
  
  node('Polarion-Testbot') {
   //checkout polarian scripts
    dir('/tmp/polarian_scripts'){
      git branch: config.polarian_update.script_branch,
          url: config.polarian_update.script_url
    }
    try{
      def tt=sh(returnStdout:true,script:"""#!/bin/bash -x
          python  /tmp/polarian_scripts/polarion_testrun_importer/release_wi_sync.py \
          --project "${config.polarian_update.PROJECT}" \
          --release "${configRuntime.project_release_version}" \
          --releaseType "${configRuntime.pipelineType}" \
          --cluster "${config.polarian_update.CLUSTER}" \
          --region "${config.polarian_update.REGION}" \
          --brand "${config.polarian_update.BRAND}" \
          --status "buildFinished"
          """)
      } catch (FlowInterruptedException e) {
        throw e
      } catch (err) {
          echo err.toString()
          emailext(body: "Error is : ${err.toString()}",
              to: config.email_recipients,
              from: config.default_email_sender,
              subject: "Polarian update failed for ${configRuntime.project_release_version}")
      } 
   }
}
