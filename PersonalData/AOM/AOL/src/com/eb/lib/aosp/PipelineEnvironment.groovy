package com.eb.lib.aosp

import jenkins.plugins.git.GitSCMSource
import jenkins.plugins.git.traits.BranchDiscoveryTrait
import org.jenkinsci.plugins.workflow.libs.SCMSourceRetriever
import org.yaml.snakeyaml.Yaml

class PipelineEnvironment implements Serializable {

  // stores project specific configuration
  def configuration = [:]

  // stores project specific properties that can be passed and modified by steps during runtime
  def configProperties = [:]

  def script

  def workingDir

  PipelineEnvironment(script) {
     this.script = script
  }

  def setupEnvironment() {
    generateEnvironmentConfiguration(globalOnly: false)
    setEnvironmentals()
  }

  def setupGlobalEnvironment() {
    generateEnvironmentConfiguration(globalOnly: true)
    setEnvironmentals()
  }

  def getWorkingDir() {
    if("${script.WORKSPACE}".tokenize('@').getAt(1) == null) {
      return "${configuration.general.defaultRootDir}/${script.JOB_BASE_NAME}_${configuration.general.variant}"
    } else {
      return "${configuration.general.defaultRootDir}/${script.JOB_BASE_NAME}_${configuration.general.variant}" + '_' + "${script.WORKSPACE}".tokenize('@').getAt(1)
    }
  }

  def loadBashLibs() {
    def defaultlib = 'eb/global/common.lib'
    def variantlib = 'eb/variant/common.lib'
    def eblib = ""
    def varlib = ""
    def devlib = ""
    try {
      eblib = script.libraryResource defaultlib
    } catch(err) {
      script.println("WARNING: ${defaultlib} not found")
    }
    try{
      varlib = script.libraryResource variantlib
    } catch(err) {
      script.println("WARNING: ${variantlib} not found")
    }
    try{
      devlib = script.devBashLib.getBashLib()
      script.println("devBashLib.groovy: ${devlib}")
    } catch(err) {
      script.println("WARNING: devBashLib.groovy not found")
    }
    script.writeFile file: "${script.WORKSPACE}/ebcommon.lib", text: eblib + "\n" + varlib + "\n" + devlib
    return "${script.WORKSPACE}/ebcommon.lib"
  }
  def copyStackTargetFile(String stockFile){
    def stockTargetFile = stockFile
    def stockText = ""
    try{
      stockText = script.libraryResource stockTargetFile
    } catch(err) {
      script.println("WARNING: ${stockTargetFile} not found")
    }
    script.writeFile file: "${script.WORKSPACE}/${stockFile}", text: stockText
  }

  def loadGlobalBashLibs() {
    def defaultlib = 'eb/global/common.lib'
    def eblib = ""
    try {
      eblib = script.libraryResource defaultlib
    } catch(err) {
      println("WARNING: ${defaultlib} not found")
    }
    script.writeFile file: "${script.WORKSPACE}/ebglobal.lib", text: eblib
    return "${script.WORKSPACE}/ebglobal.lib"
  }

  private setEnvironmentals() {
        configuration.env?.each {
          k, v -> script.env["${k}"] = v
        }
  }

  private getScm(nodeLabel) {
        if (script.scm instanceof hudson.plugins.git.GitSCM) {
            def _scm = script.node(nodeLabel){script.checkout script.scm}
            [
                branch: _scm.GIT_BRANCH.replaceAll('origin/',''),
                url: _scm.GIT_URL,
                commit:_scm.GIT_COMMIT,
                creds:"${script.scm.userRemoteConfigs.find{true}.credentialsId}",
                host: _scm.GIT_URL.tokenize(':')[1].replaceAll('^//','').tokenize('@')[-1],
                port: _scm.GIT_URL.tokenize(':')[-1].tokenize('/')[0].isNumber()?_scm.GIT_URL.tokenize(':')[-1].tokenize('/')[0]:'29418'
            ]
        } else {
            script.currentBuild.result = 'FAILURE'
            jobUtils.throwException(script, "Missing SCM Repo Entry In Job Configuration!")
        }
  }

  private loadVariantLibrary(_scm) {
    if ( script.scm instanceof hudson.plugins.git.GitSCM && _scm.creds.values[0] ) {
      script.echo " variant library details :${_scm.url}, ${_scm.creds}, ${_scm.commit}"
      script.library identifier: 'pipeline-variant-library@' + _scm.commit,
        retriever: script.modernSCM(
        [
          $class: 'GitSCMSource',
            remote: _scm.url,
            //credentialsId: '2a89571d-7562-443b-8b4d-1c1a11e72fea',
            credentialsId:_scm.creds.values[0],
            extensions: [
            [
              $class: 'SubmoduleOption',
              disableSubmodules: false,
              parentCredentials: true,
              recursiveSubmodules: true,
              trackingSubmodules: false
            ]]
        ])
    } else {
      script.library 'pipeline-variant-library'
    }
  }

  private boolean isYaml(String fileName) {
      return fileName.endsWith(".yml") || fileName.endsWith(".yaml")
  }

  private Map mergeMapRecursive(Map[] maps) {

      Map result

      if (maps.length == 0) {
          result = [:]
      } else if (maps.length == 1) {
          result = maps[0]
      } else {
          result = [:]
          maps.each { map ->
              map.each { k, v ->
                  result[k] = result[k] instanceof Map ? mergeMapRecursive(result[k], v) : v
              }
          }
      }
      return result
  }

  private Map stringToMap(String mapAsString) {

      Map result = [:]

      if (mapAsString.length() != 0) {
          def items = []
          splitStringFlat(mapAsString[1..-2].trim(), ',', items)

          items.each {
              def pair = it.trim().split(':', 2)
              pair[0] = pair[0].trim();
              pair[1] = pair[1].trim();
              result[pair.first()] = pair.last()[0] == '[' ? stringToMap(pair.last()) : pair.last()
          }
      }
      return result
  }

  private splitStringFlat(String toBeSplit, String del, ArrayList result = []) {

      def flatSplit = toBeSplit.split(':', 2)

      if (flatSplit.size() == 1) {

          result.add(toBeSplit)
          return
      }

      def pairEnd = flatSplit[0].size()
      def brackets = 0

      flatSplit[1].find {

          if (it == ',') {
              if (brackets == 0) {
                  return true
              }
          }
          if ( it == "[") {
              brackets++
          }
          if ( it == "]") {
              brackets--
          }
          pairEnd++
          return false
      }

      result.add(toBeSplit[0..pairEnd])

      if (pairEnd < toBeSplit.length()-1) {
          tail = toBeSplit[pairEnd+2..toBeSplit.length()-1]
          if (tail.length() != 0) {
              splitStringFlat(tail, del, result)
          }
      }
  }

  def generateEnvironmentConfiguration(Map args) {
      boolean globalOnly
      if (args.globalOnly == null || args.globalOnly == false) {
        globalOnly = false
      } else {
        globalOnly = true
      }


      // stores loaded configuration
      Map envConf = [:]

      // load global default pipeline configuration from yaml file
      def globalConf = loadConfigurationFromFile('')

      def variantConf = [:]

      // load optional dynamic pipeline configurations passed as string parameter
      def dynamicConf = (script.params.configuration != null) ? stringToMap(script.params.configuration) : [:]

      if (globalOnly == false){
          // load variant pipeline configuration from yaml file
          // temporary set configuration to get default node label
          globalConf.scm = getScm(script.env.NODE_NAME)
          loadVariantLibrary(globalConf.scm)

          // set default job mode on global level according to job name

          globalConf.general.defaultJob = "${script.JOB_BASE_NAME}".split("_")[0].trim().toLowerCase()

          try {
            variantConf = loadConfigurationFromFile('eb/variant/pipeline_config.yml')
          }catch (err){
	    script.echo("WARNING: Enable to read eb/variant/pipeline_config.yml from variant shared lib going with global config only ${err}")
	  }

          // merge all configurations in sequential order: global <- variant <- dynamic
          envConf = mergeMapRecursive(globalConf, variantConf, dynamicConf)
      } else {
          // merge all configurations in sequential order: global <- dynamic
          envConf = mergeMapRecursive(globalConf, dynamicConf)
      }

      if (globalOnly == false){
          // generate environment configuration based on defined job selected configuration
          // iterate over each job: jobConf.key = jobName, jobConf.value = Map of Job specific items
          for (jobConf in envConf?.job) {
              if (jobConf.key.equalsIgnoreCase(envConf.general.defaultJob)) {
                  envConf = mergeMapRecursive(envConf,jobConf.value)
                  envConf.remove('job')
              }
          }
      }

      configuration = envConf
  }

  private Map loadConfigurationFromFile(String configFile='') {
      script.env.PROJECT_CONFIG_FILE = script.env.PROJECT_CONFIG_FILE?:'pipeline_config.yml'
      String defaultYmlConfigFile =  "eb/global/aosp/" + script.env.PROJECT_CONFIG_FILE
      configFile =  configFile?:defaultYmlConfigFile

      Map configMap = [:]
      String configString = script.libraryResource configFile

      if (isYaml(configFile)) {
          configMap = script.readYaml(text: configString)
      } else {
          configMap = script.readJSON(text: configString)
      }

      return configMap
  }

  def reset() {
      configProperties = [:]
      configuration = [:]
  }

  def setConfigProperty(property, value) {
      configProperties[property] = value
  }

  def getConfigProperty(property) {
      if (configProperties[property] != null)
          return configProperties[property].trim()
      else
          return configProperties[property]
  }

  def printConfiguration() {
      script.echo '----------  JOB CONFIGURATION ----------'
      // for (item in configuration) {
      //   script.echo " --- ${item.key} --- "
      //   script.echo prettyPrint(toJson(item.value))
      //   script.echo " ------------------- "
      // }
      Yaml yaml = new Yaml()
      script.echo yaml.dump(configuration)
      script.echo '----------  END CONFIGURATION ----------'
  }

  def isParallelStepPossible(index) {
      def s = 'build' + configuration.stages['build']?.parallel?.getAt(index)
      if (s) {
          try {
            Eval.x(script, 'x.' + s)
            return true
          }
          catch (MissingPropertyException mpe) {
            return false
          }
      }
      else return false
  }

  def invokeParallelStep(index, stage) {
      Eval.x(script, 'x.' +
        stage + configuration.stages['build'].parallel[index] +
        ' script: x')
  }

  def getNode(index) {
    def s = configuration.stages['build']?.parallel?.getAt(index)
    def res = configuration.general.defaultNode
    if (s) {
      res = configuration.general[s.toLowerCase()]?.node ?: configuration.general.defaultNode
    }
    return res
  }


}
