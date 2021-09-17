/**
 *  Lift Off
 *  Space-X Launch Schedule Integration
 *  Copyright 2021 Justin Leonard
 *
 *  Licensed Virtual the Apache License, Version 2.0 (the "License"); you may not use this file except
 *  in compliance with the License. You may obtain a copy of the License at:
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software distributed under the License is distributed
 *  on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the License
 *  for the specific language governing permissions and limitations under the License.
 *
 *  Change History:
 *  v1.1.0  Full feature Beta
 *  v1.1.1  Improved update around launch time
 *  v1.1.2  Fixed issue with inactivity timing
 *  v1.1.3  Default patch
 *  v1.1.4  Fixed success/failure spacing
 *  v1.1.5  Fixed success/failure bug
 *  v1.1.6  Fixed handling of partial dates
 */

import java.text.SimpleDateFormat
import groovy.transform.Field
import groovy.time.TimeCategory

metadata
{
    definition(name: "Lift Off", namespace: "lnjustin", author: "lnjustin", importUrl: "")
    {
        capability "Configuration"
        capability "Refresh"        
        capability "Actuator"
        capability "Switch"
        
        attribute "tile", "string" 
        
        attribute "time", "string"
        attribute "timeStr", "string"
        attribute "name", "string"
        attribute "location", "string"
        attribute "rocket", "string"
        attribute "description", "string"
        attribute "status", "string"   
        attribute "coreRecovery", "string"   
    }
}

preferences
{
    section
    {
        input name: "clearWhenInactive", type: "bool", title: "Clear Tile When Inactive?", defaultValue: false
        input name: "hoursInactive", type: "number", title: "Inactivity Threshold (In Hours)", defaultValue: 24
        input name: "refreshInterval", type: "number", title: "Refresh Interval (In Minutes)", defaultValue: 120
        input name: "showName", type: "bool", title: "Show Launch Name on Tile?", defaultValue: false
        input name: "showLocality", type: "bool", title: "Show Launch Location on Tile?", defaultValue: false
      //  input name: "showRocket", type: "bool", title: "Show Rocket Name on Tile?", defaultValue: false
        input name: "dashboardType", type: "enum", options: ["Native Hubitat", "Sharptools"], title: "Dashboard Type for Which to Configure Tile", defaultValue: "Native Hubitat"
        input name: "textColor", type: "text", title: "Tile Text Color (Hex)", defaultValue: "#000000"
        input name: "logEnable", type: "bool", title: "Enable debug logging", defaultValue: true
    }
}

def logDebug(msg) 
{
    if (logEnable)
    {
        log.debug(msg)
    }
}

def getDashboardType() {
    return (dashboardType != null) ? dashboardType : "Native Hubitat"
}


def configure()
{
    logDebug("Configuring Lift Off...")
    state.clear()
    unschedule()
    refresh()
}

def refresh()
{
    setState()
    updateDisplayedLaunch()
    unschedule()
    scheduleUpdate()  
    def refreshSecs = refreshInterval ? refreshInterval * 60 : 120 * 60
    runIn(refreshSecs, refresh, [overwrite: false])
}

def setState() {
    setLatestLaunch()
    setNextLaunch()    
}

def updateDisplayedLaunch() {
    def launch = getLaunchToDisplay()
    def switchValue = getSwitchValue()  
    def tile = getTile(launch)
    updateDevice([launch: launch, switchValue: switchValue, tile: tile])  
}

def updateDevice(data) {
    sendEvent(name: "time", value: data.launch != null ? data.launch.time : "No Launch Data")
    sendEvent(name: "timeStr", value: data.launch != null ? data.launch.timeStr : "No Launch Data")
    sendEvent(name: "name", value: data.launch != null ? data.launch.name : "No Launch Data")
    sendEvent(name: "location", value: data.launch != null ? data.launch.locality : "No Launch Data")
    sendEvent(name: "rocket", value: data.launch != null ? data.launch.rocket : "No Launch Data")
    def description = ""
    if (data.launch == null) description = "No Launch Data"
    else if (data.launch.description == null) description = "No Description Available"
    else description = data.launch.description
    sendEvent(name: "description", value: description)
    sendEvent(name: "status", value: data.launch != null ? data.launch.status : "No Launch Data")
    sendEvent(name: "coreRecovery", value: data.launch != null ? data.launch.coreRecovery : "No Launch Data")
    
    sendEvent(name: "tile", value: data.tile)
    sendEvent(name: "switch", value: data.switchValue)    
}

def updateLatestLaunchStatus() {
    if (state.updateAttempts == null) state.updateAttempts = 1
    else state.updateAttempts++
        
    def storedStatus = state.latestLaunch.status
    setState()
    if (storedStatus == state.latestLaunch.status && state.updateAttempts <= 24) {
        // Keep checking for update every 5 minutes until max attempts reached
        runIn(300, updateLatestLaunchStatus)        
    }
    else if (storedStatus != state.latestLaunch.status) {
        updateDisplayedLaunch()
        state.updateAttempts = 0
    }
    else if (storedStatus == state.latestLaunch.status && state.updateAttempts > 24) {
        // max update attempts reached. Reset for next time and abort update.
        state.updateAttempts = 0
    }
}

def scheduleUpdate() {
    Date now = new Date()
    
    // update when time to switch to display next launch
    Date updateAtDate = getDateToSwitchFromLastToNextLaunch()   
    if (now.before(updateAtDate)) runOnce(updateAtDate, updateDisplayedLaunch)
    
    // update after next launch
    if (state.nextLaunch) {
        def nextLaunchTime = new Date(state.nextLaunch.time)
        def delayAfterLaunch = null
        // update launch when API likely to have new data
        use(TimeCategory ) {
           delayAfterLaunch = nextLaunchTime + 10.minutes
        }
        runOnce(delayAfterLaunch, refresh, [overwrite: false])
    }
    if (state.latestLaunch) {
        def lastLaunchTime = new Date(state.latestLaunch.time)
        def secsSinceLaunch = getSecondsBetweenDates(now, lastLaunchTime)
        if ((state.latestLaunch.status == "" || state.latestLaunch.status == null) && secsSinceLaunch < (3600 * 3)) {
            // schedule another update to occur in 10 minutes if the launch happened within the past 3 hours but the success/failure status has not yet been updated
            runIn(600, updateLatestLaunchStatus)  
        }
    }
    
    // schedule update to occur based on inactivity threshold (after latest launch and before next launch)
    
    if (state.latestLaunch && hoursInactive) {
        def lastLaunchTime = new Date(state.latestLaunch.time)
        Calendar cal = Calendar.getInstance()
        cal.setTimeZone(location.timeZone)
        cal.setTime(lastLaunchTime)
        cal.add(Calendar.HOUR, hoursInactive as Integer)
        Date inactiveDateTime = cal.time
        if (now.before(inactiveDateTime)) runOnce(inactiveDateTime, refresh, [overwrite: false])
    }
    if (state.nextLaunch && hoursInactive) {
        def nextLaunchTime = new Date(state.nextLaunch.time)
        Calendar cal = Calendar.getInstance()
        cal.setTimeZone(location.timeZone)
        cal.setTime(nextLaunchTime)
        cal.add(Calendar.HOUR, (hoursInactive * -1 as Integer))
        Date activeDateTime = cal.time
        if (now.before(activeDateTime)) runOnce(activeDateTime, refresh, [overwrite: false])
    }    
}

def getSwitchValue()  {
    def switchValue = "off"
    if (state.latestLaunch && isToday(new Date(state.latestLaunch.time))) switchValue = "on"
    if (state.nextLaunch && isToday(new Date(state.nextLaunch.time))) switchValue = "on"
    return switchValue    
}

def getTileParameters(launch) {
   def scalableFont = false
    def margin = "-25%"
    def imageWidth = "100%"
    def dashboard = getDashboardType()
    
    def numLines = 1
    if (launch.name) numLines++
   // if (showRocket) numLines++
    if (showLocality) numLines++
    if (launch.status != "Scheduled" && launch.status != null && launch.status != "null") numLines++
    
    if (dashboard == "Sharptools") {
        scalableFont = true
        margin = "-10%"
        if (numLines == 2) imageWidth = "90%"
        else if (numLines == 3) imageWidth = "70%"
        else if (numLines == 4) imageWidth = "50%"
    }
    else {
        if (numLines == 2) imageWidth = "90%"
        else if (numLines == 3) imageWidth = "90%"
        else if (numLines == 4) imageWidth = "70%"
    }

    def color = ""
    if (textColor != "#000000") color = "color: $textColor"
    
    return [scalableFont: scalableFont, imageWidth: imageWidth, margin: margin, color: color]
}

def getTile(launch) {
    def tile = "<div style='overflow:auto;'>"
    def patch = launch.patch != null ? launch.patch : "https://raw.githubusercontent.com/lnjustin/App-Images/master/Lift-Off/spacexLogo.png"
    if (!clearWhenInactive || (clearWhenInactive && !isInactive())) {
        if (launch != null) {
            def tileParameters = getTileParameters(launch)            
            tile = "<div style='text-align:center;padding:0px;height:100%;${tileParameters.color};'>"        
            tile += "<img src='${patch}' style='width:${tileParameters.imageWidth}; top:0px;'>"   
            tile += "</div>"  
            tile += "<div style='text-align:center;margin-top:${tileParameters.margin};'>"
            if (showName) tile += "<p style='margin:0px;${tileParameters.scalableFont == true ? 'font-size: min(10vh, 10vw)' : ''}'><b>${launch.name}</b></p>" 
            tile += "<p style='margin:0px;${tileParameters.scalableFont == true ? 'font-size: min(10vh, 10vw)' : ''}'>${launch.timeStr}</p>"               
          //  if (showRocket) tile += "<p style='margin:0px;${tileParameters.scalableFont == true ? 'font-size: min(10vh, 10vw)' : ''}'>${launch.rocket}</p>" 
            if (showLocality) tile += "<p style='margin:0px;${tileParameters.scalableFont == true ? 'font-size: min(10vh, 10vw)' : ''}'>${launch.locality}</p>"
            if (launch.status != "Scheduled" && launch.status != null && launch.status != "null") {
                tile += "<div style='text-align:center;margin:0px;'>"
                if (launch.status == "Launched") tile += successRocketIcon
                else if (launch.status == "Failed") tile += failureRocketIcon 
                if (launch.coreRecovery == "Success") tile += successCoreRecoveryIcon
                else if (launch.coreRecovery == "Failure") tile += failureCoreRecoveryIcon
                else if (launch.coreRecovery == "Partial Success") tile += partialSuccessCoreRecoveryIcon
                tile += "</div>"
            }
            tile += "</div>"  
        }
    }
    // If no launch to display, display nothing (keep dashboard clean)
    return tile    
}

Boolean isInactive() {
    def isInactive = false
    Date now = new Date()
    Date inactiveDateTime = null
    Date activeDateTime = null
    if (state.latestLaunch != null && hoursInactive != null) {
        def lastLaunchTime = new Date(state.latestLaunch.time)
        Calendar cal = Calendar.getInstance()
        cal.setTimeZone(location.timeZone)
        cal.setTime(lastLaunchTime)
        cal.add(Calendar.HOUR, hoursInactive as Integer)
        inactiveDateTime = cal.time
        logDebug("Inactivity Post-Launch scheduled to start ${inactiveDateTime}")        
    }
    if (state.nextLaunch != null && (state.nextLaunch.timePrecision == 'day' || state.nextLaunch.timePrecision == 'hour') && hoursInactive != null) {
        def nextLaunchTime = new Date(state.nextLaunch.time)
        Calendar cal = Calendar.getInstance()
        cal.setTimeZone(location.timeZone)
        cal.setTime(nextLaunchTime)
        cal.add(Calendar.HOUR, (hoursInactive * -1 as Integer))
        activeDateTime = cal.time
        logDebug("Inactivity Pre-Launch scheduled to stop ${activeDateTime}")
        
    }   
    if (inactiveDateTime != null && activeDateTime != null) {
        if (now.after(inactiveDateTime) && now.before(activeDateTime)) isInactive = true
    }
    else if (inactiveDateTime == null && activeDateTime != null) {
        if (now.before(activeDateTime)) isInactive = true
    }
    else if (inactiveDateTime != null && activeDateTime == null) {
        if (now.after(inactiveDateTime)) isInactive = true
    }
    if (isInactive) logDebug("No launch activity within the past ${hoursInactive} hour(s) and within the next ${hoursInactive} hour(s). ${clearWhenInactive ? "Hiding tile." : ""}")
    return isInactive
}

def setLatestLaunch() {
    def latest = httpGetExec("launches/latest")
    def unixTimestamp = (latest.date_unix as Long) * 1000
    def launchTime = new Date(unixTimestamp)
    def launchStatus = ""
    if (latest.success != null && latest.success != "null") {
        if (latest.success == true || latest.success == "true") launchStatus = "Launched"
        else if (latest.success == false || latest.success == "false") launchStatus = "Failed"
    }
    def coreRecovery = "Not Applicable"
    if (latest.cores != null && latest.cores != "null") {
        def atLeastOneCoreSuccess = null
        def atLeastOneCoreFailure = null
        for (core in latest.cores) {
           if (core.landing_attempt == true && core.landing_success == true) atLeastOneCoreSuccess = true
           else if (core.landing_attempt == true && core.landing_success == false) atLeastOneCoreFailure = true
        }
        if (atLeastOneCoreSuccess == null && atLeastOneCoreFailure == null) coreRecovery = "Recovery Not Attempted" // no landing attempts made, so no status shown
        else if (atLeastOneCoreSuccess == true && atLeastOneCoreFailure == null) coreRecovery = "Success" // succeeded for all landing attempts
        else if (atLeastOneCoreSuccess == true && atLeastOneCoreFailure == true) coreRecovery = "Partial Success" // succeeded for only some landing attempts            
        else if (atLeastOneCoreSuccess == null && atLeastOneCoreFailure == true) coreRecovery = "Failure" // failed for all landing attempts        
    }
    def locality = getLocality(latest)    
    def rocketName = getRocketName(latest)
    
    state.latestLaunch = [time: launchTime.getTime() , timeStr: getTimeStr(launchTime),  name: latest.name, description: latest.details, locality: locality, rocket: rocketName, patch: latest.links.patch.large, status: launchStatus, coreRecovery: coreRecovery]
}

def setNextLaunch() {
    def next = httpGetExec("launches/next")
    def unixTimestamp = (next.date_unix as Long) * 1000
    def launchTime = new Date(unixTimestamp)    
    def launchTimePrecision = next.date_precision
    def locality = getLocality(next)    
    def rocketName = getRocketName(next)
    
    state.nextLaunch = [time: launchTime.getTime(), timeStr: getTimeStr(launchTime, launchTimePrecision), timePrecision: launchTimePrecision, name: next.name, description: next.details, locality: locality, rocket: rocketName, patch: next.links.patch.large, status: "Scheduled", coreRecovery: "Not Applicable"]    
}

def getLocality(launch) {
    def launchPadID = launch.launchpad
    def launchPad = httpGetExec("launchpads/" + launchPadID)
    return launchPad?.locality    
}

def getRocketName(launch) {
    def rocketID = launch.rocket
    def rocket = httpGetExec("rockets/" + rocketID)
    return rocket?.name    
}

def getLaunchToDisplay() {
    def launch = null
    if (state.latestLaunch == null && state.nextLaunch != null) launch = state.nextLaunch
    else if (state.nextLaunch == null && state.latestLaunch != null) launch = state.latestLaunch
    else if (state.latestLaunch != null && state.nextLaunch != null) {
        def now = new Date()        
        Date updateAtDate = getDateToSwitchFromLastToNextLaunch()
        logDebug("Date for switching to next launch is: ${updateAtDate}")
        if (now.after(updateAtDate) || now.equals(updateAtDate)) {
            launch = state.nextLaunch
            logDebug("Displaying next launch.")
        }
        else {
            launch = state.latestLaunch
            logDebug("Displaying last launch.")
        }
    }
    return launch
}

def getDateToSwitchFromLastToNextLaunch() {
    if (!state.latestLaunch || !state.nextLaunch) {
        return null
        log.error "No launch in state."
    }
    def lastLaunchTime = new Date(state.latestLaunch.time)
    def nextLaunchTime = new Date(state.nextLaunch.time)
    def now = new Date()
    Date date = null
    def minsBetweenLaunches = Math.round(getSecondsBetweenDates(lastLaunchTime, nextLaunchTime) / 60)         
    if (minsBetweenLaunches < 1440) {
        // if less than 24 hours between launches, switch to next launch halfway between launches
        logDebug("Less than 24 hours between launches. Switching to display next launch halfway between launches")
        if (now.after(nextLaunchTime)) date = now // if launch is already scheduled to start, switch now
        else {
            def switchTime = Math.round(getSecondsBetweenDates(now, nextLaunchTime) / 120) as Integer // switch halfway between now and the next launch time
            Calendar cal = Calendar.getInstance()
            cal.setTimeZone(location.timeZone)
            cal.setTime(lastLaunchTime)
            cal.add(Calendar.MINUTE, switchTime as Integer)
            date = cal.time
        }
    }
    else {
        // switch to display next launch 1 day after the last launch
        date = lastLaunchTime + 1
        logDebug("More than 24 hours between launches. Switching to display next launch 24 hours after the last launch.")
    }
    return date   
}

def isToday(Date date) {
    def isToday = false
    def today = new Date().clearTime()
    def dateCopy = new Date(date.getTime())
    def dateObj = dateCopy.clearTime()
    if (dateObj.equals(today)) isToday = true
    return isToday
}


def isYesterday(Date date) {
    def isYesterday = false
    def today = new Date().clearTime()
    def yesterday = today - 1
    def dateCopy = new Date(date.getTime())
    def dateObj = dateCopy.clearTime()    
    if (dateObj.equals(yesterday)) isYesterday = true
    return isYesterday
}

String getTimeStr(Date launchTime, launchTimePrecision = null) {
    def timeStr = ""
  
    if (launchTimePrecision == null || launchTimePrecision == 'hour') {
        def timeStrPrefix = ""
        def nextWeek = new Date() + 7
        def lastWeek = new Date() - 7
        def now = new Date()
        def dateFormat = null
        if (launchTime.after(nextWeek)) dateFormat = new SimpleDateFormat("EEE, MMM d h:mm a")
        else if (isToday(launchTime)) {
           timeStrPrefix = "Today "
            dateFormat = new SimpleDateFormat("h:mm a")
        }
        else if (isYesterday(launchTime)) {
            timeStrPrefix = "Yesterday "
            dateFormat = new SimpleDateFormat("h:mm a")
        }
        else if (launchTime.before(lastWeek)) dateFormat = new SimpleDateFormat("EEE, MMM d h:mm a")
        else if (launchTime.before(now)) {
             timeStrPrefix = "Last "   
             dateFormat = new SimpleDateFormat("EEE h:mm a")
        }
        else dateFormat = new SimpleDateFormat("EEE h:mm a")
        dateFormat.setTimeZone(location.timeZone)        
        timeStr = timeStrPrefix + dateFormat.format(launchTime)    
    }
    else if (launchTimePrecision == 'day') {
        def dateFormat = new SimpleDateFormat("EEE")
        dateFormat.setTimeZone(TimeZone.getTimeZone("UTC")) // Intentionally avoid translating to local time zone
        timeStr = dateFormat.format(launchTime)   
        
        def now = new Date()
        def today = dateFormat.format(now)
        if (timeStr == today) timeStr = "Today"
    }
    else if (launchTimePrecision == 'month') {
        def dateFormat = new SimpleDateFormat("MMMMM")
        dateFormat.setTimeZone(TimeZone.getTimeZone("UTC")) // Intentionally avoid translating to local time zone
        timeStr = dateFormat.format(launchTime) 
        
        def now = new Date()
        def thisMonth = dateFormat.format(now)
        if (timeStr == thisMonth) timeStr = "This Month"
    }
    else if (launchTimePrecision == 'year') {
        def dateFormat = new SimpleDateFormat("yyyy")
        dateFormat.setTimeZone(TimeZone.getTimeZone("UTC"))  // Intentionally avoid translating to local time zone
        timeStr = dateFormat.format(launchTime)
        
        def now = new Date()
        def thisYear = dateFormat.format(now)
        if (timeStr == thisYear) timeStr = "This Year"
    }
    // TO DO: handle 'quarter' and 'half' precision values
    return timeStr
}

def getSecondsBetweenDates(Date startDate, Date endDate) {
    try {
        def difference = endDate.getTime() - startDate.getTime()
        return Math.round(difference/1000)
    } catch (ex) {
        log.error "getSecondsBetweenDates Exception: ${ex}"
        return 1000
    }
}

def updated()
{
    configure()
}

def uninstalled()
{

}

def httpGetExec(suffix)
{
    logDebug("Space-X: httpGetExec(${suffix})")
    
    try
    {
        getString = "https://api.spacexdata.com/v4/" + suffix
        httpGet(getString.replaceAll(' ', '%20'))
        { resp ->
            if (resp.data)
            {
              //  logDebug("resp.data = ${resp.data}")
                return resp.data
            }
        }
    }
    catch (Exception e)
    {
        log.warn "Space-X httpGetExec() failed: ${e.message}"
    }
}
    
@Field static successRocketIcon = '<svg xmlns="http://www.w3.org/2000/svg" xmlns:xlink="http://www.w3.org/1999/xlink" width="19pt" height="19pt" viewBox="0 0 19 19" version="1.1"><g id="surface1"><path style=" stroke:none;fill-rule:nonzero;fill:green;fill-opacity:1;" d="M 13.207031 12.398438 L 13.207031 9.976562 C 13.207031 4.582031 10.925781 1.320312 9.96875 0.195312 C 9.867188 0.0703125 9.714844 0 9.554688 0 C 9.394531 0 9.246094 0.0703125 9.140625 0.191406 C 8.167969 1.316406 5.839844 4.574219 5.839844 9.976562 L 5.839844 12.398438 L 5.394531 12.699219 C 4.53125 13.28125 4.015625 14.25 4.015625 15.289062 L 4.015625 18.179688 C 4.015625 18.347656 4.109375 18.507812 4.261719 18.589844 C 4.410156 18.667969 4.59375 18.660156 4.738281 18.566406 L 6.1875 17.597656 C 6.597656 17.328125 7.074219 17.183594 7.5625 17.183594 L 8.632812 17.183594 L 8.632812 18.582031 C 8.632812 18.839844 8.839844 19.046875 9.097656 19.046875 L 9.949219 19.046875 C 10.207031 19.046875 10.414062 18.839844 10.414062 18.582031 L 10.414062 17.183594 L 11.480469 17.183594 C 11.972656 17.183594 12.449219 17.324219 12.859375 17.597656 L 14.308594 18.566406 C 14.449219 18.660156 14.632812 18.667969 14.785156 18.589844 C 14.9375 18.507812 15.03125 18.347656 15.03125 18.179688 L 15.03125 15.289062 C 15.03125 14.253906 14.511719 13.28125 13.652344 12.699219 Z M 9.523438 8.605469 C 8.636719 8.605469 7.917969 7.886719 7.917969 7 C 7.917969 6.113281 8.636719 5.398438 9.523438 5.398438 C 10.410156 5.398438 11.128906 6.113281 11.128906 7 C 11.128906 7.886719 10.410156 8.605469 9.523438 8.605469 Z M 9.523438 8.605469 "/></g></svg>'
@Field static failureRocketIcon = '<svg xmlns="http://www.w3.org/2000/svg" xmlns:xlink="http://www.w3.org/1999/xlink" width="19pt" height="19pt" viewBox="0 0 19 19" version="1.1"><g id="surface1"><path style=" stroke:none;fill-rule:nonzero;fill:red;fill-opacity:1;" d="M 13.207031 12.398438 L 13.207031 9.976562 C 13.207031 4.582031 10.925781 1.320312 9.96875 0.195312 C 9.867188 0.0703125 9.714844 0 9.554688 0 C 9.394531 0 9.246094 0.0703125 9.140625 0.191406 C 8.167969 1.316406 5.839844 4.574219 5.839844 9.976562 L 5.839844 12.398438 L 5.394531 12.699219 C 4.53125 13.28125 4.015625 14.25 4.015625 15.289062 L 4.015625 18.179688 C 4.015625 18.347656 4.109375 18.507812 4.261719 18.589844 C 4.410156 18.667969 4.59375 18.660156 4.738281 18.566406 L 6.1875 17.597656 C 6.597656 17.328125 7.074219 17.183594 7.5625 17.183594 L 8.632812 17.183594 L 8.632812 18.582031 C 8.632812 18.839844 8.839844 19.046875 9.097656 19.046875 L 9.949219 19.046875 C 10.207031 19.046875 10.414062 18.839844 10.414062 18.582031 L 10.414062 17.183594 L 11.480469 17.183594 C 11.972656 17.183594 12.449219 17.324219 12.859375 17.597656 L 14.308594 18.566406 C 14.449219 18.660156 14.632812 18.667969 14.785156 18.589844 C 14.9375 18.507812 15.03125 18.347656 15.03125 18.179688 L 15.03125 15.289062 C 15.03125 14.253906 14.511719 13.28125 13.652344 12.699219 Z M 9.523438 8.605469 C 8.636719 8.605469 7.917969 7.886719 7.917969 7 C 7.917969 6.113281 8.636719 5.398438 9.523438 5.398438 C 10.410156 5.398438 11.128906 6.113281 11.128906 7 C 11.128906 7.886719 10.410156 8.605469 9.523438 8.605469 Z M 9.523438 8.605469 "/></g></svg>'

@Field static successCoreRecoveryIcon = '<svg xmlns="http://www.w3.org/2000/svg" xmlns:xlink="http://www.w3.org/1999/xlink" width="20pt" height="20pt" viewBox="0 0 20 20" version="1.1"><g id="surface1"><path style=" stroke:none;fill-rule:nonzero;fill:green;fill-opacity:1;" d="M 9.9375 19.738281 C 15.414062 19.738281 19.871094 15.308594 19.871094 9.867188 C 19.871094 4.429688 15.414062 0 9.9375 0 C 4.457031 0 0 4.429688 0 9.867188 C 0 15.308594 4.457031 19.738281 9.9375 19.738281 Z M 9.9375 1.773438 C 14.429688 1.773438 18.085938 5.40625 18.085938 9.867188 C 18.085938 14.332031 14.429688 17.964844 9.9375 17.964844 C 5.441406 17.964844 1.785156 14.332031 1.785156 9.867188 C 1.785156 5.40625 5.441406 1.773438 9.9375 1.773438 Z M 9.9375 1.773438 "/><path style=" stroke:none;fill-rule:nonzero;fill:green;fill-opacity:1;" d="M 9.933594 15.554688 C 13.226562 15.554688 15.902344 12.894531 15.902344 9.625 C 15.902344 6.355469 13.226562 3.695312 9.933594 3.695312 C 6.644531 3.695312 3.964844 6.355469 3.964844 9.625 C 3.964844 12.894531 6.644531 15.554688 9.933594 15.554688 Z M 9.933594 5.464844 C 12.238281 5.464844 14.117188 7.332031 14.117188 9.625 C 14.117188 11.917969 12.238281 13.78125 9.933594 13.78125 C 7.628906 13.78125 5.75 11.917969 5.75 9.625 C 5.75 7.332031 7.628906 5.464844 9.933594 5.464844 Z M 9.933594 5.464844 "/><path style=" stroke:none;fill-rule:nonzero;fill:green;fill-opacity:1;" d="M 11.484375 9.582031 C 11.484375 10.433594 10.789062 11.125 9.933594 11.125 C 9.078125 11.125 8.382812 10.433594 8.382812 9.582031 C 8.382812 8.730469 9.078125 8.042969 9.933594 8.042969 C 10.789062 8.042969 11.484375 8.730469 11.484375 9.582031 Z M 11.484375 9.582031 "/></g></svg>'
@Field static failureCoreRecoveryIcon = '<svg xmlns="http://www.w3.org/2000/svg" xmlns:xlink="http://www.w3.org/1999/xlink" width="20pt" height="20pt" viewBox="0 0 20 20" version="1.1"><g id="surface1"><path style=" stroke:none;fill-rule:nonzero;fill:red;fill-opacity:1;" d="M 9.9375 19.738281 C 15.414062 19.738281 19.871094 15.308594 19.871094 9.867188 C 19.871094 4.429688 15.414062 0 9.9375 0 C 4.457031 0 0 4.429688 0 9.867188 C 0 15.308594 4.457031 19.738281 9.9375 19.738281 Z M 9.9375 1.773438 C 14.429688 1.773438 18.085938 5.40625 18.085938 9.867188 C 18.085938 14.332031 14.429688 17.964844 9.9375 17.964844 C 5.441406 17.964844 1.785156 14.332031 1.785156 9.867188 C 1.785156 5.40625 5.441406 1.773438 9.9375 1.773438 Z M 9.9375 1.773438 "/><path style=" stroke:none;fill-rule:nonzero;fill:red;fill-opacity:1;" d="M 9.933594 15.554688 C 13.226562 15.554688 15.902344 12.894531 15.902344 9.625 C 15.902344 6.355469 13.226562 3.695312 9.933594 3.695312 C 6.644531 3.695312 3.964844 6.355469 3.964844 9.625 C 3.964844 12.894531 6.644531 15.554688 9.933594 15.554688 Z M 9.933594 5.464844 C 12.238281 5.464844 14.117188 7.332031 14.117188 9.625 C 14.117188 11.917969 12.238281 13.78125 9.933594 13.78125 C 7.628906 13.78125 5.75 11.917969 5.75 9.625 C 5.75 7.332031 7.628906 5.464844 9.933594 5.464844 Z M 9.933594 5.464844 "/><path style=" stroke:none;fill-rule:nonzero;fill:red;fill-opacity:1;" d="M 11.484375 9.582031 C 11.484375 10.433594 10.789062 11.125 9.933594 11.125 C 9.078125 11.125 8.382812 10.433594 8.382812 9.582031 C 8.382812 8.730469 9.078125 8.042969 9.933594 8.042969 C 10.789062 8.042969 11.484375 8.730469 11.484375 9.582031 Z M 11.484375 9.582031 "/></g></svg>'
@Field static partialSuccessCoreRecoveryIcon = '<svg xmlns="http://www.w3.org/2000/svg" xmlns:xlink="http://www.w3.org/1999/xlink" width="20pt" height="20pt" viewBox="0 0 20 20" version="1.1"><g id="surface1"><path style=" stroke:none;fill-rule:nonzero;fill:green;fill-opacity:1;" d="M 9.9375 19.738281 C 15.414062 19.738281 19.871094 15.308594 19.871094 9.867188 C 19.871094 4.429688 15.414062 0 9.9375 0 C 4.457031 0 0 4.429688 0 9.867188 C 0 15.308594 4.457031 19.738281 9.9375 19.738281 Z M 9.9375 1.773438 C 14.429688 1.773438 18.085938 5.40625 18.085938 9.867188 C 18.085938 14.332031 14.429688 17.964844 9.9375 17.964844 C 5.441406 17.964844 1.785156 14.332031 1.785156 9.867188 C 1.785156 5.40625 5.441406 1.773438 9.9375 1.773438 Z M 9.9375 1.773438 "/><path style=" stroke:none;fill-rule:nonzero;fill:red;fill-opacity:1;" d="M 9.933594 15.554688 C 13.226562 15.554688 15.902344 12.894531 15.902344 9.625 C 15.902344 6.355469 13.226562 3.695312 9.933594 3.695312 C 6.644531 3.695312 3.964844 6.355469 3.964844 9.625 C 3.964844 12.894531 6.644531 15.554688 9.933594 15.554688 Z M 9.933594 5.464844 C 12.238281 5.464844 14.117188 7.332031 14.117188 9.625 C 14.117188 11.917969 12.238281 13.78125 9.933594 13.78125 C 7.628906 13.78125 5.75 11.917969 5.75 9.625 C 5.75 7.332031 7.628906 5.464844 9.933594 5.464844 Z M 9.933594 5.464844 "/><path style=" stroke:none;fill-rule:nonzero;fill:green;fill-opacity:1;" d="M 11.484375 9.582031 C 11.484375 10.433594 10.789062 11.125 9.933594 11.125 C 9.078125 11.125 8.382812 10.433594 8.382812 9.582031 C 8.382812 8.730469 9.078125 8.042969 9.933594 8.042969 C 10.789062 8.042969 11.484375 8.730469 11.484375 9.582031 Z M 11.484375 9.582031 "/></g></svg>'
