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
 *  v1.1.3  
patch
 *  v1.1.4  Fixed success/failure spacing
 *  v1.1.5  Fixed success/failure bug
 *  v1.1.6  Fixed handling of partial dates
 *  v2.0.0  Switched to new API after old API deprecated
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
        
        attribute "time", "number"
        attribute "timeStr", "string"
        attribute "name", "string"
        attribute "location", "string"
        attribute "rocket", "string"
        attribute "description", "string"
        attribute "status", "string"   
        attribute "statusDetail", "string"  
    }
}

preferences
{
    section
    {
      //  input name: "launchAgencyFilter", type: "text", title: "Name of Launch Agency based on which to Filter Results (default 'SpaceX')", defaultValue: "SpaceX"
        input name: "clearWhenInactive", type: "bool", title: "Clear Tile When Inactive?", defaultValue: false
        input name: "hoursInactive", type: "number", title: "Inactivity Threshold (In Hours)", defaultValue: 24
        input name: "refreshInterval", type: "number", title: "Refresh Interval (In Minutes) (Mininum 5 mins)", defaultValue: 120
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
 //   setLatestLaunch()
 //   setNextLaunch()    
    def launches = httpGetExec("launch/upcoming/")?.results
    def now = new Date()
    
    def latest = null
    def next = null
    
    for (launch in launches) {
        def launchTime = toDateTime(launch.net)
        if (latest == null && launchTime < now) latest = launch
        if (launchTime >= now) {
            next = launch
            break
        }        
    }
    
    logDebug("Latest Launch: ${latest}")
    logDebug("Next Launch: ${next}")
    
    if (latestLaunch != null) {
        def launch = latest
        def launchTime = toDateTime(launch.net)
        def patch = (launch.mission_patches != null) ? launch.mission_patches[0]?.image_url : null
        state.latestLaunch = [time: launchTime.getTime() , timeStr: getTimeStr(launchTime),  name: launch.mission?.name, provider: launch.launch_service_provider?.name, description: launch.mission?.description, locality: launch.pad?.location.name, rocket: launch.rocket?.configuration?.name + " " + launch.rocket?.configuration?.variant, patch: patch, status: launch.status.abbrev, statusDetail: launch.status.description]
    }
    
    if (next != null) {
        def launch = next
        def launchTime = toDateTime(launch.net)
        def patch = (launch.mission_patches != null) ? launch.mission_patches[0]?.image_url : null
        state.nextLaunch = [time: launchTime.getTime() , timeStr: getTimeStr(launchTime),  name: launch.mission?.name, provider: launch.launch_service_provider?.name, description: launch.mission?.description, locality: launch.pad?.location.name, rocket: launch.rocket?.configuration?.name + " " + launch.rocket?.configuration?.variant, patch: patch, status: launch.status.abbrev, statusDetail: launch.status.description]
    }
    else state.nextLaunch = null
}

def updateDisplayedLaunch() {
    def launch = getLaunchToDisplay()
    def switchValue = getSwitchValue()  
    def tile = getTile(launch)
    updateDevice([launch: launch, switchValue: switchValue, tile: tile])  
}

def updateDevice(data) {
    sendEvent(name: "time", value: (data.launch != null) ? data.launch.time : "No Launch Data")
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
    sendEvent(name: "statusDetail", value: data.launch != null ? data.launch.statusDetail : "No Launch Data")
    
    sendEvent(name: "tile", value: data.tile)
    sendEvent(name: "switch", value: data.switchValue)    
}

def updateLatestLaunchStatus() {
    if (state.updateAttempts == null) state.updateAttempts = 1
    else state.updateAttempts++
        
    def storedStatus = state.latestLaunch.status
    setState()
    if (storedStatus == state.latestLaunch.status && state.updateAttempts <= 24) {
        // Keep checking for update every 10 minutes until max attempts reached
        runIn(600, updateLatestLaunchStatus)        
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
    if (updateAtDate != null && now.before(updateAtDate)) runOnce(updateAtDate, updateDisplayedLaunch)
    
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
    if (launch.status != "Go" && launch.status != "TBC" && launch.status != "TBD" && launch.status != null && launch.status != "null") numLines++
    
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
            tile += "<p style='margin:0px;${tileParameters.scalableFont == true ? 'font-size: min(2vh, 2vw)' : ''}'>${launch.timeStr}</p>"               
          //  if (showRocket) tile += "<p style='margin:0px;${tileParameters.scalableFont == true ? 'font-size: min(10vh, 10vw)' : ''}'>${launch.rocket}</p>" 
            if (showLocality) tile += "<p style='margin:0px;${tileParameters.scalableFont == true ? 'font-size: min(10vh, 10vw)' : ''}'>${launch.locality}</p>"
            if (launch.status != null && launch.status != "null" && (launch.status.contains("Success") || launch.status.contains("Failure"))) {
                tile += "<div style='text-align:center;margin:0px;'>"
                if (launch.status.contains("Success")) tile += successRocketIcon
                else if (launch.status.contains("Failure")) tile += failureRocketIcon 
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

String getTimeStr(Date launchTime) {
    def timeStr = ""
  
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
    logDebug("Lift Off: httpGetExec(${suffix})")
    
    try
    {
        def searchStr = "&search=SpaceX"
   //     getString = "https://lldev.thespacedevs.com/2.2.0/" + suffix + "?format=json&mode=detailed" + searchStr    // Development API
        getString = "https://ll.thespacedevs.com/2.2.0/" + suffix + "?format=json&mode=detailed" + searchStr    // Live API
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
        log.warn "Lift Off httpGetExec() failed: ${e.message}"
    }
}
    
@Field static successRocketIcon = '<svg xmlns="http://www.w3.org/2000/svg" xmlns:xlink="http://www.w3.org/1999/xlink" width="19pt" height="19pt" viewBox="0 0 19 19" version="1.1"><g id="surface1"><path style=" stroke:none;fill-rule:nonzero;fill:green;fill-opacity:1;" d="M 13.207031 12.398438 L 13.207031 9.976562 C 13.207031 4.582031 10.925781 1.320312 9.96875 0.195312 C 9.867188 0.0703125 9.714844 0 9.554688 0 C 9.394531 0 9.246094 0.0703125 9.140625 0.191406 C 8.167969 1.316406 5.839844 4.574219 5.839844 9.976562 L 5.839844 12.398438 L 5.394531 12.699219 C 4.53125 13.28125 4.015625 14.25 4.015625 15.289062 L 4.015625 18.179688 C 4.015625 18.347656 4.109375 18.507812 4.261719 18.589844 C 4.410156 18.667969 4.59375 18.660156 4.738281 18.566406 L 6.1875 17.597656 C 6.597656 17.328125 7.074219 17.183594 7.5625 17.183594 L 8.632812 17.183594 L 8.632812 18.582031 C 8.632812 18.839844 8.839844 19.046875 9.097656 19.046875 L 9.949219 19.046875 C 10.207031 19.046875 10.414062 18.839844 10.414062 18.582031 L 10.414062 17.183594 L 11.480469 17.183594 C 11.972656 17.183594 12.449219 17.324219 12.859375 17.597656 L 14.308594 18.566406 C 14.449219 18.660156 14.632812 18.667969 14.785156 18.589844 C 14.9375 18.507812 15.03125 18.347656 15.03125 18.179688 L 15.03125 15.289062 C 15.03125 14.253906 14.511719 13.28125 13.652344 12.699219 Z M 9.523438 8.605469 C 8.636719 8.605469 7.917969 7.886719 7.917969 7 C 7.917969 6.113281 8.636719 5.398438 9.523438 5.398438 C 10.410156 5.398438 11.128906 6.113281 11.128906 7 C 11.128906 7.886719 10.410156 8.605469 9.523438 8.605469 Z M 9.523438 8.605469 "/></g></svg>'
@Field static failureRocketIcon = '<svg xmlns="http://www.w3.org/2000/svg" xmlns:xlink="http://www.w3.org/1999/xlink" width="19pt" height="19pt" viewBox="0 0 19 19" version="1.1"><g id="surface1"><path style=" stroke:none;fill-rule:nonzero;fill:red;fill-opacity:1;" d="M 13.207031 12.398438 L 13.207031 9.976562 C 13.207031 4.582031 10.925781 1.320312 9.96875 0.195312 C 9.867188 0.0703125 9.714844 0 9.554688 0 C 9.394531 0 9.246094 0.0703125 9.140625 0.191406 C 8.167969 1.316406 5.839844 4.574219 5.839844 9.976562 L 5.839844 12.398438 L 5.394531 12.699219 C 4.53125 13.28125 4.015625 14.25 4.015625 15.289062 L 4.015625 18.179688 C 4.015625 18.347656 4.109375 18.507812 4.261719 18.589844 C 4.410156 18.667969 4.59375 18.660156 4.738281 18.566406 L 6.1875 17.597656 C 6.597656 17.328125 7.074219 17.183594 7.5625 17.183594 L 8.632812 17.183594 L 8.632812 18.582031 C 8.632812 18.839844 8.839844 19.046875 9.097656 19.046875 L 9.949219 19.046875 C 10.207031 19.046875 10.414062 18.839844 10.414062 18.582031 L 10.414062 17.183594 L 11.480469 17.183594 C 11.972656 17.183594 12.449219 17.324219 12.859375 17.597656 L 14.308594 18.566406 C 14.449219 18.660156 14.632812 18.667969 14.785156 18.589844 C 14.9375 18.507812 15.03125 18.347656 15.03125 18.179688 L 15.03125 15.289062 C 15.03125 14.253906 14.511719 13.28125 13.652344 12.699219 Z M 9.523438 8.605469 C 8.636719 8.605469 7.917969 7.886719 7.917969 7 C 7.917969 6.113281 8.636719 5.398438 9.523438 5.398438 C 10.410156 5.398438 11.128906 6.113281 11.128906 7 C 11.128906 7.886719 10.410156 8.605469 9.523438 8.605469 Z M 9.523438 8.605469 "/></g></svg>'
