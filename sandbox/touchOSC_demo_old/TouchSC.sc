TouchSC {
	
	classvar <synthLib;
	// copyArgs
	var iPadIP;
	// instance vars
	var <synth, <group, <iPadAddr, <myIP, <controls, <testResp, iPadAddr = nil;
	
	*initClass {
		synthLib = CtkProtoNotes(
			
			// Note: you can use the synthdef to store other parametric information that isn't used 
			// directly by the synth, but which you can store in arguments for the sake of updating
			// the GUI later ("dummyarg" argument in the synth below).  
			// For example, if you're using a oscillator on a control bus to drive 
			// parameters, you may not be able to access the oscillating frequency directly later.  
			// So in addition to setting the frequency of the control oscillator (like setting a 
			// NodeProxy.source = {SinOsc.ar(freq)}), you would also set the synth argument to "freq".
			// While this may seem redundant, it makes accessing relevant controls for gui updates easier
			// from a programatic standpoint by only having to access the synths parameters instead of
			// multiple control structures. See the updateControls method below.
			
			SynthDef(\myInstrument_ipad, {arg amp, freq, gate=1,
				// these args just used for bookkeeping
				dummyarg=0;
				
				var env, sine;
					env = EnvGen.kr( Env([0,1,0],[1,1], \sin, releaseNode: 1 ), gate, doneAction: 2 );
					sine = SinOsc.ar(freq, 0, amp.dbamp);
					Out.ar(0, sine * env );
			})
		)
	}
	
	*new { | iPadIP |
		^super.newCopyArgs(iPadIP).init;
	}
	
	
	// init just sets up communication and prompts the user to confirm that
	// OSC communication is working properly.  This is so that if the communication
	// is NOT working, there's no stranded nodes or synths created and left unattended.
	// Note that the "begin" method functions as a more typical "init" structure.
	
	init {
		iPadAddr = NetAddr( iPadIP, 58100 ); // 58100 is the default TouchOSC port
		this.loadIP;		// set myIP
		this.testOSCgui;	// make sure communication is working
	}
	
	free {
		group.deepFree;
		this.removeResponders;  // IMPORTANT
		controls = nil;
	}
	
	
	// Update the iPad with default controls. Useful for startup.
	// Iterates through the controls dictionary for ControlSpec defaults.
	
	sendDefaultControls {
		
		controls.keysValuesDo({ | controlname, attributes, i |
			var spec, sndtag;
			
			// Some controls, like toggles, won't have ControlSpecs
			// or sndtags because they don't have labels.
			spec = attributes.atFail( \spec, {nil} );
			sndtag = attributes.atFail( \sndtag, {nil} );
			
			spec.notNil.if({ 
					iPadAddr.sendMsg(attributes.rcvtag, spec.unmap(spec.default)) });
			sndtag.notNil.if({
					iPadAddr.sendMsg(sndtag, spec.default.asString);
				})
			});
		
		// Send other default states for things like GUI "tags" that don't accept 
		// control values as their value, as a control "label" does (which defaults to 
		// control defaults).
		iPadAddr.sendMsg('/playtogtag', "Stopped"); // this tag defaults to "Stopped", red
		iPadAddr.sendMsg('/playtogtag/color', "red");
		// iPadAddr.sendMsg ...
	}
	
	
	// Note that the "begin" method functions as a more typical "init" structure.
	begin {
		
		this.loadControls;
		this.createResponders;
		
		// The master group is useful if you'll be creating many synths and want to 
		// be able to free them all together
		group = CtkGroup.play( server: Server.default );
		
		// Upon creating the synth, you can send initial parameters based on the 
		// defaults you specify in the control library specs. Don't play yet.
		synth = synthLib[\myInstrument_ipad].new(addAction: \tail, target: group)
				.freq_(controls.freq.spec.default)
				.amp_(controls.amp.spec.default);

		this.sendDefaultControls; // update the gui to its default state
	}
	
	
	controlNames {
		this.controls.keys.asArray.do({ |name| name.postln; });
	}
	
	// get attributes of a control
	getAttributes { | controlName |
		^this.controls[controlName.asSymbol];
	}
	
	loadControls {
		
		controls = IdentityDictionary.new(know: true)
			
			//////////////////
			//// A Slider ////
			//////////////////		
			
			// "know: true" is very useful!! and necessary for how other methods in this calss
			// access this info.  See IdentityDictionary helpfile..
			.put( \amp, IdentityDictionary.new(know: true)  
				.put( \spec, ControlSpec( 9, -30, \lin, 0.1, 0) )
				.put( \action, 
					{{ | outval |
						("amp: " + outval).postln;
						synth.amp_(outval);
					}})
				)
			
			.put( \freq, IdentityDictionary.new(know: true)
				.put( \spec, ControlSpec( 400, 1300, \lin, 1, 600) )
				.put( \action, 
					{{ | outval |
						("freq: " + outval).postln;
						synth.freq_(outval);
					}})
				)
		
			//////////////////
			//// A Toggle ////
			//////////////////
						
			.put( \playtog, IdentityDictionary.new(know: true)
				.put( \spec, ControlSpec( 0, 1, \lin, 0, 0) ) // optional
				.put( \action, 
					{{ | outval |
						(outval == 1).if({
							synth.freq_(controls.freq.spec.default)
								.amp_(controls.amp.spec.default)
								.play;
							// uncomment the line below to be able to 
							// restart with the same parameters it ended with
							// synth.play; 
							
							// optional state "tag" for the gui
							iPadAddr.sendMsg('/playtogtag', "Playing");
							iPadAddr.sendMsg('/playtogtag/color', "green");
						},{
							synth.release;
							// comment out line below if you want to
							// restart with the same parameters it ended with
							this.sendDefaultControls; // reset the controls in the iPad
						});
					}})
				)
				//  Note: while toggles don't need specs because they're just either
				//  1 or 0, it could be useful to give them a spec with a range of
				//  0 > 1, with a step size of 1.  This would allow you to ustilize 
				//  the "default" state for re-initializing your instrument

	}
	
		
	// This structure isn't used in this example, but is useful to reference
	// for updating the gui to match current states of a synth.  For example, if your
	// GUI toggles between controlling different instances of the same synth
	// running simultaneously (like multiple sound beams).  This is where storing dummy 
	// values in synth arguments is useful.
	updateControls {
		var ctls, srctag;
		
		// these control names are from a synth's arguments (not the above synth in this example)
		ctls = [\amp, \amfreq, \steer, \spread, \panfreq, \panmax, \panmin, \spreadfreq, \spreadmax, \spreadmin];
		ctls.do({ |ctl|
			var lval, cval; // label val, control val
			lval = synth.args.at(ctl);
			cval = controls[ctl].spec.unmap(lval); //remap with the spec for 0>1 val
			iPadAddr.sendMsg('/'++ctl++'L', lval);
			iPadAddr.sendMsg('/'++ctl, cval);
		});
		// Also update controls not stored as variable names in the synth
		// iPadAddr.sendMsg...
	}
	
	createResponders {
		var test;
		// Make sure there's not already responders loaded
		// if so, remove them.
		test = controls.playtog.atFail(\responder, {nil});
		test.notNil.if({ this.removeResponders });
		
		// Add a receive tag and a send tag for each control.
		// The receive tag is set to be the name of the key of this
		// control.  The send tag, is the same name with an "L" 
		// appended on the end.  Make sure these match label names you
		// set in TouchOSC.
		controls.keysValuesDo({ |controlname, attributes, i|
			attributes.put( \rcvtag, '/' ++ controlname.asSymbol );
			attributes.put( \sndtag, '/' ++ controlname.asSymbol ++ 'L' );
		});
		
		controls.keysValuesDo({ |controlname, attributes, i| 
						controlname.postln; 
						attributes.put(\responder, 
							{	var action, rcvtag, sndtag, spec;
								
								rcvtag = attributes.rcvtag;								action = attributes.action;
								spec = attributes.atFail(\spec, {nil}); // could move into the responder for real-time updates
								sndtag = attributes.atFail(\sndtag, {nil});
								
								OSCresponderNode(nil, rcvtag, { |t, r, msg| 
									var outval;
									
									// msg[1] (the iPad control value) should always be 
									// a 0>1 value, as that's what ControlSpec expects.
									// Make sure that's the range set in TouchOSC.
									outval = spec.notNil.if({ spec.map(msg[1]) },{ msg[1] });
									
									// Execute the function in the "action" attribute 
									// in the control dictionary
									action.value( outval );
									
									// Update the label if a \sndtag exists
									sndtag.notNil.if({
										iPadAddr.sendMsg(sndtag, outval);
									});
								}).add;
							}.value;
						)
					})
	}

	
	// IMPORTANT!!
	removeResponders  {
		controls.keysValuesDo({ |controlname, attributes, i| 
			controlname.post; attributes.responder.remove; " responder removed".postln; })
	}
	
	
	// create a NetAddr for the iPad where we send our messages
	setIPadAddr { | iPadIP |
		iPadAddr = NetAddr( iPadIP, 58100 );
	}
	
	
	// Automatically store your IP address, update myIP variable
	loadIP {
		var func;
		func = { arg action;
			var before = NetAddr.broadcastFlag;
			NetAddr.broadcastFlag = true;
			OSCresponder(nil, '/getMyIP', { arg t,r,msg,addr;
				action.(addr);
				NetAddr.broadcastFlag = before;
				r.remove;
			}).add;
		
			NetAddr("255.255.255.255", NetAddr.langPort).sendMsg('/getMyIP');
			nil;
		};
		
		func.value({arg addr; 
				("Set the IP in TouchOSC to: " ++ addr.ip ++ " and port: "++NetAddr.langPort).postln;
				myIP = addr.ip;
			});
	}
	
	
	// A useful check to confirm the OSC messaging is working properly before starting
	// your instrument. Uses the amp parameter as a default to check, so make sure the 
	// testResp is listening for a valid OSC tag in your instrument ( '/amp' in this case ).
	
	testOSCgui {
		var win, dec, testbutY, testbutN, fbText, ipInput, submit;
		
		testResp = OSCresponderNode(nil, '/amp', { |t, r, msg, addr| 
				"Amp fader value received.".postln;
				iPadAddr.sendMsg("/ampL", msg[1].linlin(0,1,9, -60))
			}).add;
		
		win = Window( "Testing iPad Communitcation", Rect(300, 200, 400, 400) );
		dec = win.addFlowLayout( 5@5, 5@5 );
		SCStaticText(win, win.bounds.width@40)
					.string_("Is the amplitude lablel displaying the appropriate values?");
		RoundButton( win, 100@20 )
					.states_([[ "Yes, Start up!", Color.black, Color.green]] )
					.action_({ this.begin; win.close });
		RoundButton( win, 60@20 )
					.states_([[ "No", Color.black, Color.red]] )
					.action_({ 
						fbText.string_( 
								"Confirm TouchOSC is sending to the right IP address. This computer's IP is:    " ++ myIP.asString ++ "      This computer is expecting messages from and iPad with an IP of:    " ++ iPadAddr.ip ++ "     If these aren't correct, reset this computer's IP to make sure SC is using airport (it may take a few moments after switching network settings). It may default to ethernet even if airport is on.  If the iPad's address isn't correct, you can update it..." )
							
						});
		dec.nextLine;
		fbText = SCStaticText(win, win.bounds.width-10@190);
		
		dec.nextLine;
		
		SCStaticText(win, 80@20)
					.string_("Enter iPad IP:");
		ipInput = SCTextField(win, Rect(10, 10, 170, 20))
					.action_({ submit.doAction });
		submit = RoundButton( win, 100@20 )
					.states_([[ "Update iPad IP", Color.black, Color.yellow]] )
					.action_({ 
						iPadAddr = NetAddr( ipInput.value.asString, 58100 );
						fbText.string_("New iPad IP Address is " ++ iPadAddr.ip ++ " is communication working?");
					});

		dec.nextLine; dec.nextLine;

		SCStaticText(win, win.bounds.width@50)
					.string_("Sometimes SC needs to be reminded of your computer's IP if you've recently changed the settings...");
		RoundButton( win, 100@20 )
					.states_([[ "Update my IP", Color.black, Color.yellow]] )
					.action_({ 
						this.loadIP;
						fbText.string_(myIP.asString);
					});
		win.onClose_({ testResp.remove; });
		win.front;
	}
	
}