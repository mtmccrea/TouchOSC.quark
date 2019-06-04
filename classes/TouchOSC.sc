TouchOSC {
	classvar <>uniqueIDs;
	// copyArgs
	var <ip, <inPort, <lockOnDeviceIP, <verticalLayout;
	var <>debug=false, <devRcvAddr, <devSndAddr, <controls, <uniqueID;

	//	if lockOnDeviceIP is set, controls will only respond to that ip combination
	*new { |deviceIPString, deviceIncomingPort, lockOnDeviceIP=true, verticalLayout = false|
		^super.newCopyArgs(deviceIPString, deviceIncomingPort, lockOnDeviceIP, verticalLayout).init;
	}

	init {
		devRcvAddr = NetAddr(ip, inPort);	// addr/port SC sends messages to
		devSndAddr = NetAddr(ip, nil);		// addr SC listens to if lockOnDeviceIP=true,
		// nil port because send port from device is
		// unknown/unreliable
		controls = IdentityDictionary(know: true);

		// initialize class var
		TouchOSC.uniqueIDs ?? {TouchOSC.uniqueIDs = []};
	}

	addCtl { | name, kind, oscTag, spec, label, postValue=true, roundPost |
		var tag, ctl, oscDefName;

		oscTag.notNil.if({
			tag = oscTag;

			oscDefName = this.getUniqueOscDefName(name.asSymbol);
			this.prCheckDuplicateControlName(oscDefName, name.asSymbol);
			this.prCheckDuplicateResponderTags(tag);

			ctl = TouchOSCControl(this,
				// name.asSymbol, kind, tag, spec, label, postValue,
				oscDefName, kind, tag, spec, label, postValue,
				if(lockOnDeviceIP,{devSndAddr},{nil}),
                roundPost
			);

			// store in TouchOSC object by name (not it's unique OSCdef name)
			controls.put(name.asSymbol, ctl);

		},{
			var cond = Condition(false);

			fork {
				block { |break|

					tag = this.prOneShotRespForOscTag(cond); // this waits for a response

					tag ?? { break.("control mapping timed out".warn) };
					debug.if{ "tag: ".post; tag.postln };

					oscDefName = this.getUniqueOscDefName(name.asSymbol);

					this.prCheckDuplicateControlName(oscDefName, name.asSymbol);
					this.prCheckDuplicateResponderTags(tag);

					ctl = TouchOSCControl(this,
						// name.asSymbol, kind, tag, spec, label, postValue,
						oscDefName, kind, tag, spec, label, postValue,
						if(lockOnDeviceIP,{devSndAddr},{nil}),
                        roundPost
					);

					// store in TouchOSC object by name
					controls.put(name.asSymbol, ctl);
				}
			}
		})
	}

	// connect a control to a function or a ctkNoteObject
	connect { |object ... ctlNameTargetPairs|

		ctlNameTargetPairs.clump(2).do{|pairs,i|
			var ctlname, target, ctl, action;

			#ctlname, target = pairs;         // target is the function or synth param name
			ctl = controls[ctlname.asSymbol]; // the control's dictionary
			ctl.notNil.if({
				block { |break|
					action = switch( target.class,
						Symbol, {
                            if (object.isKindOf(CtkNode)) {
                                {|widgetVal| object.set(0, target, widgetVal)}
                            }{
                                if (object.respondsTo(target)) {
                                    if (object.isKindOf(View)) {
                                        { |widgetVal| defer {object.perform(target, widgetVal)} } // return a func, deferred for gui objects
                                    }{
                                        { |widgetVal| object.perform(target, widgetVal) } // return a func
                                    }
                                }{
                                    break.(
                                        format("object doesn't respond to %", target).warn
                                    )
                                }
                            }
						},
						Function, {
							{|widgetVal| target.(object, widgetVal)}
						},
						{ // default
                            break.("control target isn't a synth parameter or a function".warn)
						}
					);
					ctl.action_(action);
				}
			},{
				warn(format("Control % not found, double check the control name.", ctlname.asSymbol))
			});
		};
	}

	disconnect { |... controlNames|
		controlNames.do{|name| this.prDisconnectAndRestoreMapFunc(name.asSymbol)}
	}

	disconnectAll {
		var keys;
		keys = controls.keys;
		keys.do{|name| this.prDisconnectAndRestoreMapFunc(name)};
	}

	// remap a control's ControlSpec, reconnecting to an object as needed
	remap { |ctlName, controlSpec|
		var ctl;
		ctl = controls[ctlName.asSymbol];
		ctl.notNil.if(
			{ ctl.remap(controlSpec) },
			{ warn("Control name not found.") }
		);
	}

	prCheckDuplicateControlName { |oscDefName, ctlname|
		var warnTest=false, msg="";

		OSCdef(oscDefName).func.notNil.if{
			warnTest = true;
			msg = format("Overwriting another OSCdef named %.", oscDefName);
		};

		if(controls.keys.includes(ctlname), {
			warnTest = true;
			msg = msg + format(
                    "% was already in use by this instance of TouchOsc "
                    "and its controls may have already been mapped to a "
                    "synth or object parameter.",
                    ctlname
                );

			if(controls[ctlname].connected,
				{
					this.disconnect(ctlname);
					msg = msg + format("Disconnecting % and remapping its ControlSpec.", ctlname);
				},{
					msg = msg + format("Remapping the previous ControlSpec for %.", ctlname) }
			)
		});

		warnTest.if{ warn(msg) };
	}

	prOneShotRespForOscTag {|completeCondition|
		var oscFunc, tag;

		fork { 5.wait; completeCondition.test_(true).signal }; // timeout
		"oscTag is nil, waiting for control to be touched...".postln;

		oscFunc = { |msg, time, addr, recvPort|
			var ipMatchFail;

			ipMatchFail = (lockOnDeviceIP and: (addr.ip != ip));

			if( msg[0] != '/status.reply', {
				block {|break|
					if( ipMatchFail,
						{
							break.(
                                warn("ip doesn't match this instance of TouchOSC, "
                                    "map again with the correct device IP")
                            )
						},{
							tag = msg[0].asSymbol;
							postf("Tag received: %\n", msg[0].asSymbol);
						}
					)
				};
				completeCondition.test_(true).signal;
			})
		};
		thisProcess.addOSCRecvFunc(oscFunc);
		debug.if{ "waiting".postln }; // debug
		completeCondition.wait;
		debug.if{ "done waiting".postln }; // debug
		thisProcess.removeOSCRecvFunc(oscFunc);
		^tag
	}

	prCheckDuplicateResponderTags { |tag|
		var globalOSCTags=[], dupTest;
		AbstractResponderFunc.allFuncProxies.keysValuesDo{ |k, v|
			v.do({|osc|
				if (osc.isKindOf(OSCFunc) or: osc.isKindOf(OSCdef)) {
					globalOSCTags = globalOSCTags.add(osc.path)
				}
			})
		};
		dupTest = globalOSCTags.includes(tag);

		dupTest.if{
			var localDuplicates, dupMsg;
			localDuplicates = controls.select{|ctl| ctl.oscTag == tag };
			dupMsg = if(localDuplicates.size > 0,
				{ format("This instance's controls use it: %.", localDuplicates.keys) },
				{ "Not in use, however, by this _instance_ of TouchOsc." }
			);
			warn("This control's OSC tag is already in use."
                + dupMsg + "Multiple responders may react to single controls. "
                "Consider changing this control's OSC tag ('name')\n");
		};
	}

	prDisconnectAndRestoreMapFunc {|ctlname|
		var ctl;
		ctl = controls[ctlname];
		ctl !? {
			// clear the old one and replace (necessary because of sc bug see below)
			debug.if{"Freeing: ".post; OSCdef(ctl.name).postln;};
			OSCdef(ctl.name).clear.free;

			// restore the original mapFunc
			ctl.responder = OSCdef(ctl.name, ctl.mapFunc, ctl.oscTag, ctl.devSndAddr);
			ctl.connected = false;
		}
	}

	getUniqueOscDefName { |name|

		// make sure this instance has a uniqueID assigned
		uniqueID ?? {
			var cnt = 0;

			while( {this.class.uniqueIDs.includes(cnt) and: (cnt<=100)}, { cnt = cnt+1 });

			(cnt > 100).if{
				warn("unique name not found in 100 tries, likely overwriting another or some other problem.")
            };

			uniqueID = cnt;
			this.class.uniqueIDs = this.class.uniqueIDs.add(uniqueID);
		};

		^(name.asSymbol ++ uniqueID).asSymbol;
	}

	clearControls {
		controls.do{|ctl| ctl.responder.free};
	}

	free {
		this.clearControls;
	}
}

TouchOSCControl {
	//copyArgs
	var <tOSC, <name, <kind, <oscTag, <>spec, <label, <postValue, <devSndAddr, <>roundPost;
	var <action, <>connected, <>mapFunc, <>responder, <labelTag;

	*new {|aTouchOSC, name, kind, oscTag, spec, label, postValue=true, devSndAddr, roundPost|
		^super.newCopyArgs(aTouchOSC, name, kind, oscTag, spec, label, postValue, devSndAddr, roundPost).init;
	}

	init {
		action = nil;
		connected = false;

		// update the control's name label text
		labelTag = if( (kind == \multifader) or: (kind == \multipush),
			{ var str;
				str = oscTag.asString;
				(str.replaceAt("_", str.findBackwards("/"))++'_L').asSymbol
			},
			{ (oscTag++'_L').asSymbol }
		);

		// TODO: strip unique ID from name for the label update message
		tOSC.devRcvAddr.sendMsg( labelTag, (label ?? name).asString );

        roundPost = roundPost ?? 0.01;

		spec ?? {spec = ControlSpec()};
		this.setMappingResponder;
	}

	setMappingResponder {
		mapFunc = { |msg, time, addr, recvPort|
			var inval, mappedval;

			switch( kind,
				\push,      { inval = msg[1] },
				\toggle,    { inval = msg[1] },
				\fader,     { inval = msg[1] },
				\rotary,    { inval = msg[1] },
				\multifader,{ inval = msg[1] },
				\multipush, { inval = msg[1] },
				\xy,        { inval = if( tOSC.verticalLayout,
					{ [msg[1], 1-msg[2]] },
					{ [msg[2], msg[1]] }
				)
				}
			);

			mappedval = spec.map(inval);

			tOSC.debug.if{ postf(
				"TouchOsc reading message from: %\nmapping: % > %\n",
				addr, inval.round(0.0001), mappedval.round(0.0001)) };
			postValue.if{
				var resptag;

				resptag = if( kind == \multifader,
					{ var str;
						str = oscTag.asString;
						(str.replaceAt("_", str.findBackwards("/"))++'_V').asSymbol
					},
					{ (oscTag++'_V').asSymbol }
				);

				tOSC.devRcvAddr.sendMsg( resptag,
					mappedval.round(roundPost).asString+spec.units
			)};
			mappedval; // returned the mapped value from the function
		};

		OSCdef(name).free; // just in case, see SC bug described below
		responder = OSCdef(name, mapFunc, oscTag, devSndAddr);
		connected = false;
	}

	action_ { |aFunction|
		// clear the old one and replace
		// (necessary because of sc bug see below)
		tOSC.debug.if{"Freeing: ".post; OSCdef(name).postln;};
		OSCdef(name).clear.free;

		action = aFunction;
		responder = OSCdef(name,
			{ 	arg msg, time, addr, recvPort;
				var widgetVal;
				// forward args to original mapfunc to get
				// the control's mapped value
				widgetVal = mapFunc.(msg, time, addr, recvPort);
				action.(widgetVal);
			},
			oscTag, devSndAddr
		);
		connected = true;
	}

	remap { |controlSpec|
		var connectedBeforeRemap;
		spec = controlSpec;
		connectedBeforeRemap = connected;
		this.setMappingResponder; // this sets connected=false
		if(connectedBeforeRemap, {
			// clear the old one and replace, see sc bug see below
			tOSC.debug.if{"Freeing: ".post; OSCdef(name).postln;};
			OSCdef(name).clear.free;

			responder = OSCdef(name,
				{ 	arg msg, time, addr, recvPort;
					var widgetVal;
					// forward args to orig map func to get control's mapped value
					widgetVal = mapFunc.(msg, time, addr, recvPort);
					action.(widgetVal);
				},
				oscTag, devSndAddr
			);
			connected = true;
		});
	}

}

/*
// steps

-- create a network from your computer (see network button in menu bar)
-- connect to this network on the ipad
-- relaunch touchOsc if it was already open (good practice in general)
-- in TouhOSC navigate to CONNECTIONS > OSC
-- -- enter your computer's IP address in the HOST field, 57120 (SC's default) in PORT (outgoing)
-- instantiate a TouchOsc in SC with the IP in the LOCAL IP ADDRESS field, and Port in PORT (INCOMING)



////  SC BUG ? /////

( // create an OSCdef with an original function
d = OSCdef(\testOSC, { |msg, time, addr, recvPort|
\ORIGINAL.postln;
}, '/ping', nil
); // def style
)
AbstractResponderFunc.allFuncProxies // there it is
AbstractResponderFunc.allFuncProxies['OSC unmatched'].size

n = NetAddr("localhost", 57120)
n.sendMsg('/ping', "asdf")

// now add a function
f = {|msg, time, addr, recvPort| "FIRST added function".postln;}
OSCdef(\testOSC).add(f)
n.sendMsg('/ping', "asdf")

// clear the OSCdef
OSCdef(\testOSC).clear
n.sendMsg('/ping', "asdf") // it's still executing the functions
// free the OSCdef
OSCdef(\testOSC).free
n.sendMsg('/ping', "asdf") // it's still executing the functions
// yet...
AbstractResponderFunc.allFuncProxies
// no longer on the funcProxies list !!

// need to recompile now to get rid of that headless responder
*/