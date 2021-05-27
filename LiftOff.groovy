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
    
    sendEvent(name: "tile", value: data.tile)
    sendEvent(name: "switch", value: data.switchValue)    
}

def updateLatestLaunchStatus() {
    if (state.updateAttempts == null) state.updateAttempts = 1
    else state.updateAttempts++
        
    def storedStatus = state.latestLaunch.status
    setState()
    if (storedStatus == state.latestLaunch.status && state.updateAttempts <= 12) {
        // Keep checking for update every 5 minutes until max attempts reached
        runIn(300, updateLatestLaunchStatus)        
    }
    else if (storedStatus != state.latestLaunch.status) {
        updateDisplayedLaunch()
        state.updateAttempts = 0
    }
    else if (storedStatus == state.latestLaunch.status && state.updateAttempts > 12) {
        // max update attempts reached. Reset for next time and abort update.
        state.updateAttempts = 0
    }
}

def scheduleUpdate() {
    Date now = new Date()
    
    // update when time to switch to display next launch
    Date updateAtDate = getDateToSwitchFromLastToNextLaunch()   
    if (now.before(updateAtDate)) runOnce(updateAtDate, updateLatestLaunchStatus)
    
    // update after next launch
    // TO DO: identify best time to refresh
    if (state.nextLaunch) {
        def nextLaunchTime = new Date(state.nextLaunch.time)
        def delayAfterLaunch = null
        // update launch when API likely to have new data
        use(TimeCategory ) {
           delayAfterLaunch = nextLaunchTime + 3.minutes
        }
        runOnce(delayAfterLaunch, refresh, [overwrite: false])
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
    if (!clearWhenInactive || (clearWhenInactive && !isInactive())) {
        if (launch != null) {
            def tileParameters = getTileParameters(launch)            
            tile = "<div style='text-align:center;padding:0px;height:100%;${tileParameters.color};'>"        
            tile += "<img src='${launch.patch}' style='width:${tileParameters.imageWidth}; top:0px;'>"   
            tile += "</div>"  
            tile += "<div style='text-align:center;margin-top:${tileParameters.margin};'>"
            if (showName) tile += "<p style='margin:0px;${tileParameters.scalableFont == true ? 'font-size: min(10vh, 10vw)' : ''}'><b>${launch.name}</b></p>" 
            tile += "<p style='margin:0px;${tileParameters.scalableFont == true ? 'font-size: min(10vh, 10vw)' : ''}'>${launch.timeStr}</p>"               
          //  if (showRocket) tile += "<p style='margin:0px;${tileParameters.scalableFont == true ? 'font-size: min(10vh, 10vw)' : ''}'>${launch.rocket}</p>" 
            if (showLocality) tile += "<p style='margin:0px;${tileParameters.scalableFont == true ? 'font-size: min(10vh, 10vw)' : ''}'>${launch.locality}</p>"
            if (launch.status != "Scheduled" && launch.status != null && launch.status != "null") tile += "<p>${launch.status}</p>" 
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
    if (state.nextLaunch != null && hoursInactive != null) {
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
    def status = latest.success == true ? "Success" : "Failure"   
    def locality = getLocality(latest)    
    def rocketName = getRocketName(latest)
    
    state.latestLaunch = [time: launchTime.getTime() , timeStr: getTimeStr(launchTime),  name: latest.name, description: latest.details, locality: locality, rocket: rocketName, patch: latest.links.patch.large, status: status]
}

def setNextLaunch() {
    def next = httpGetExec("launches/next")
    def unixTimestamp = (next.date_unix as Long) * 1000
    def launchTime = new Date(unixTimestamp)    
    def locality = getLocality(next)    
    def rocketName = getRocketName(next)
    
    state.nextLaunch = [time: launchTime.getTime(), timeStr: getTimeStr(launchTime),  name: next.name, description: next.details, locality: locality, rocket: rocketName, patch: next.links.patch.large, status: "Scheduled"]    
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
        if (now.after(updateAtDate) || now.equals(updateAtDate)) launch = state.nextLaunch
        else launch = state.latestLaunch
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

String getTimeStr(Date launchTime) {
    def nextWeek = new Date() + 7
    def lastWeek = new Date() - 7
    def now = new Date()
    def dateFormat = null
    def timeStrPrefix = ""
    if (launchTime.after(nextWeek)) dateFormat = new SimpleDateFormat("EEE, MMM d h:mm a")
    else if (isToday(launchTime)) {
        timeStrPrefix = "Today "
        dateFormat = new SimpleDateFormat("h:mm a")
    }
    else if (launchTime.before(lastWeek)) dateFormat = new SimpleDateFormat("EEE, MMM d h:mm a")
    else if (launchTime.before(now)) {
         timeStrPrefix = "This Past "   
        dateFormat = new SimpleDateFormat("EEE h:mm a")
    }
    else dateFormat = new SimpleDateFormat("EEE h:mm a")
    dateFormat.setTimeZone(location.timeZone)        
    def timeStr = timeStrPrefix + dateFormat.format(launchTime)    
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
    
