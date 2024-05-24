package com.eb.lib
/** **********************************************************************************************************
 * Search all Jobs and enable buildDiscarder if not on
 *
 * @param maxDayToKeep - the maximum days builds should be kept
 * @param maxBuildToKeep - the number of builds to keep
 * @param customList - custome input for particular list of jobs.
 *
 * @returns jobsFound - map of Job found without buildDiscarder on and owners (who have changed the job history)
 */
def findAndEnableBuildDiscard(maxDayToKeep, maxBuildToKeep, customList, ignoreAppliedList) {

    def jobsFound = [:]

    /* Find all jobs that do not have buildDiscarder on and enable according to input parameters */
    Jenkins.instance.getAllItems(Job.class).each { item ->
        def notifyJobOwner = false
        if (customList.any { it.contains(item.fullName) }) {
            if ((ignoreAppliedList == "false") || (!item.buildDiscarder)) {
                def discarderSet = []
                discarderSet = getDiscarderSet(item.fullName, customList)
                println "Discard Old Builds with Custome Sets for ${item.fullName}."
                println "  Enabling with: ${discarderSet[0]} days & ${discarderSet[1]} builds"
                item.buildDiscarder = new hudson.tasks.LogRotator(discarderSet[0], discarderSet[1])
                item.save()
                notifyJobOwner = true
            }
        } else {
            if (!item.buildDiscarder) {
                println "Discard Old Builds not on for ${item.fullName}."
                println "Enabling with defaults: ${maxDayToKeep} days & ${maxBuildToKeep} builds"
                item.buildDiscarder = new hudson.tasks.LogRotator(maxDayToKeep, maxBuildToKeep)
                item.save()
                notifyJobOwner = true
            } else {
                /* Check if current settings are over the default limits */
                item.buildDiscarder.with() { bd ->
                    currentDaysToKeep = bd.daysToKeep ? bd.daysToKeep.toInteger() : -1
                    currentNumToKeep = bd.numToKeep ? bd.numToKeep.toInteger() : -1
                }
                if ( currentDaysToKeep > maxDayToKeep) {
                    println "Current Days to keep builds for ${item.fullName} is too high. " +
                            "Reducing to ${maxDayToKeep}..."
                    currentDaysToKeep = maxDayToKeep
                    item.buildDiscarder = new hudson.tasks.LogRotator(currentDaysToKeep, currentNumToKeep)
                    item.save()
                    notifyJobOwner = true
                }
                if (maxBuildToKeep > 0 && currentNumToKeep > maxBuildToKeep) {
                    println "Current Number of builds to keep for ${item.fullName} is too high. " +
                            "Reducing to ${maxBuildToKeep}..."
                    item.buildDiscarder = new hudson.tasks.LogRotator(currentDaysToKeep, maxBuildToKeep)
                    item.save()
                    notifyJobOwner = true
                }
            }
        }

        /* Generate list of owners to notify if necessary*/
        if (notifyJobOwner) {
            def jobOwners = getOwnersByConfigHistory(item.fullName)
            jobsFound[item.fullName] = jobOwners
        }
    }
    return jobsFound
}

def getDiscarderSet (jobName, customList) {
    def discarderSet = []
    customList.each { item ->
        if (item.contains(jobName)) {
            def discarderSetList = item.strip().split(";|,")
            def discarderSetListLeng = discarderSetList.size()
            def daysToKeep = -1
            def buildToKeep = -1
            if (discarderSetListLeng > 2) {
                daysToKeep = discarderSetList[discarderSetListLeng-2].strip() ? discarderSetList[discarderSetListLeng-2].strip().toInteger() : -1
                buildToKeep = discarderSetList[discarderSetListLeng-1].strip() ? discarderSetList[discarderSetListLeng-1].strip().toInteger() : -1
            }
            discarderSet = [daysToKeep,buildToKeep]
        }
    }
    return discarderSet
}

/** **********************************************************************************************************
 * Search all Job config history and return list of users that modified the config
 *
 * @param jobName - the job name for which to search the config history
 * @param maxFiles - the maximum number of history files to search.  Default is 30.
 *
 * @returns list of users that modified the job config history or null if no config history
 */
def getOwnersByConfigHistory(jobName, maxFiles=30) {

    def jenkinsHome = System.getenv('JENKINS_HOME')

    /* Check Job config history location.  If no history found, return null */
    def configHistoryLocation = "${jenkinsHome}/config-history/jobs/" + jobName.replaceAll(/\//, "/jobs/")
    def folderJobHistory = new File(configHistoryLocation)
    if (folderJobHistory.exists()) {
        historyFiles = new FileNameFinder().getFileNames(configHistoryLocation, '**/history.xml')
    } else {
        return null
    }

    /* Only search the maxinum number of files set by maxFiles */
    if (historyFiles.size() > maxFiles) {
        historyFiles = historyFiles.takeRight(maxFiles)
    }
    def configUsers = []
    historyFiles.each {
        def xml = new XmlSlurper().parse(it)
        /* Only add a config user once and ignore the SYSTEM and "anonymous" users*/
        def configUser = xml['userId'].toString()
        if (configUser != 'SYSTEM' && configUser != 'anonymous' && !configUsers.contains(configUser)) {
            configUsers.add(configUser)
        }
    }

    return configUsers

}
