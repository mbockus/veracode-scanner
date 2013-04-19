/*
 * Copyright (c) 2011 Rackspace Hosting
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy of this software and associated documentation files (the "Software"), to deal in the Software without restriction, including without limitation the rights to use, copy, modify, merge, publish, distribute, sublicense, and/or sell copies of the Software, and to permit persons to whom the Software is furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in all copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY, FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM, OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE SOFTWARE.
 */

package org.jenkinsci.plugins.veracodescanner.model;

import hudson.model.Cause;
import hudson.triggers.SCMTrigger;
import hudson.triggers.TimerTrigger;
import org.kohsuke.stapler.DataBoundConstructor;

import java.util.HashMap;
import java.util.Map;

/**
 * Created by IntelliJ IDEA.
 * User: john.madrid
 * Date: 5/3/11
 * Time: 10:34 AM
 * To change this template use File | Settings | File Templates.
 */
public class BuildTriggers {

    private Map<Class<? extends Cause>, Boolean> causes;

    @DataBoundConstructor
    public BuildTriggers(boolean triggerPeriodically, boolean triggerScm, boolean triggerManually) {
        causes = new HashMap<Class<? extends Cause>, Boolean>();

        causes.put(SCMTrigger.SCMTriggerCause.class, triggerScm);
        causes.put(Cause.UserCause.class, triggerManually);
        causes.put(TimerTrigger.TimerTriggerCause.class, triggerPeriodically);
    }

    public boolean isTriggerPeriodically() {
        return causes.get(TimerTrigger.TimerTriggerCause.class);
    }

    public boolean isTriggerScm() {
        return causes.get(SCMTrigger.SCMTriggerCause.class);
    }

    public boolean isTriggerManually() {
        return causes.get(Cause.UserCause.class);
    }

    public boolean isTriggeredBy(Class<? extends Cause> cause) {
        Boolean triggered = causes.get(cause);

        return triggered!=null?triggered:false;
    }
}
