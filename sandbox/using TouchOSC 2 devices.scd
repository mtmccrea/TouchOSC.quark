/*

Using the TouchOSC class.

For this example, create 2 faders in the TouchOSC layout app. Also add 2 text labels for
each fader. One will be set to your control name, the other will be updated with the
control's value as you change it.

You can choose your own OSC tag for the faders, or for convenience leave their OSC tag
set to "auto" in the TouchOSC layout editor app if you don't want to name each control yourself.
However, you'll need to make sure the labels' OSC tags match that of your
control widget (e.g. the fader),
then append '_L' and '_V' to the OSC tags for the "label" and "value" labels, respectively.

For example, if you fader's OSC tag was auto-named '/1/fader1', you'll need to set your
label name's OSC tag to '/1/fader1_L', and the value label's OSC tag to '/1/fader1_V'.


*** Sync'ing your layout to your iOS device ***

1) Create a network from your computer (see network icon in menu bar).

2) Connect to this network on the ipad.

3) Once the layout is finalized in the Editor, click the 'Sync' button.

4) Open the TouchOSC app, navigat to 'LAYOUT' > ADD. Your layout should sync with TouchOSC Editor.
If not, make sure your iOS device is on the same network as your computer running TouchOSC Editor.

5) Select it to load it. Navigate back in the menu and click 'Done'. You should see your layout.


*** Setting up proper OSC connections ***

1) If you just connected to your computer's network, and if TouchOSC was already open, relaunch the app (good practice in general).

2) In TouhOSC navigate to CONNECTIONS > OSC. Enter your computer's IP address in the HOST field, in PORT (outgoing) enter 57120 (SC's default)

3) Proceed below to instantiate a TouchOsc in SC.

*/

// start 2 synths to control
(
s.waitForBoot({ fork {
	// the object you want to control with your TouchOSC GUI
	d = CtkSynthDef(\OSCTest, { arg outbus=2, synthfreq=300, amp=0.5;
		Out.ar(outbus, SinOsc.ar(synthfreq, mul: amp));
	});
	s.sync;
	~synth1 = d.note.outbus_(2).play;
	~synth2 = d.note.outbus_(3).play;
	};
	s.scope(2, 2);
});
)

// Note: navigate to TouchOSC's CONNECTIONS > OSC tab

// deviceIPString: the IP of the device in TouchOSC's LOCAL IP ADDRESS field,
// deviceIncomingPort: TouchOSC's PORT (INCOMING) field
// lockOnDeviceIP: optional, if specified, TouchOsc instance will only listen to that device
// verticalLayout: a boolean specifying whether your touchOSC layout is vertical or not. This affects slider behavior. Default is false (horizontal)
~tosc_1 = TouchOSC( "169.254.170.208", 9000);
// for some feedback when you're mapping controls, you can set debug to true (set before mapping)
~tosc_1.debug_(true);

// ADD controls

(
// map your fader to gain
~tosc_1.addCtl(
	name: \gain1,	// NOTE unique id added to the name if you you want more than 1
	kind: \fader,	// valid kinds are: \toggle, \fader, \xy
	oscTag: nil,	// if set to nil, that triggers an auto-detect process that listens for the first incoming message
	spec: ControlSpec(-80,3,\db,0,0,"dB"), // note "units" will be displayed in TouchOSC of specified
	label: 'myGain'	// optional label to display in TouchOSC instead of 'name' above
)
)
// now touch the fader you want to use for gain... you should see the label and values update
// if an incoming control message isn't caught in 5 seconds, it times out and you need to .addCtl again

// now map your next fader to \freq1
~tosc_1.addCtl(\freq1, \fader, nil, ControlSpec(300, 5000, \exp, units: "Hz"));
// now touch the fader you want to use for freq...

// Your device is now sending values within the specified range!
// Though the fader isn't yet controlling your synth yet, you need to
// "CONNECT" your controller (TouchOSC) to objects, in this case the above synths.

// You can connect your controls to a function or a CtkNote synth parameter.
// If connecting to a function, the function will take the object as its first parameter,
// followed by the mapped value from your control (fader)

// Connect your \gain1 fader to a target function, and connect your \freq1 fader to
// the CtkNote's freq arg. This functionality works specifically with CtkNotes (using .set under the hood)

// .connect args:
// 		object (the object to control),
// 		... pairs of control names (specified above with .addCtl) and targets (functions or CtkNote params), comma separated

(
~tosc_1.connect(
	~synth1,
	\gain1, {|obj, val| obj.amp_(val.dbamp)},	// function to evaluate when receiving a \gain1 control message
												// note: obj is ~synth1
	\freq1, \synthfreq							// parameter in ~synth1 to forward \freq1 message value to
)
)
// you can remap the range of the control on the fly
~tosc_1.remap(\gain1, ControlSpec(-35,-0.5,'db',units:"dB"))

~tosc_1.disconnect(\gain1, \freq1)

// view it
s.scope(2,2);



/* add a second device and another instance of TouchOSC to respond to it */

~tosc_2 = TouchOSC( "169.254.170.209", deviceIncomingPort: 9000, lockOnDeviceIP: true);
~tosc_2.debug_(true);

// ADD controls: map your faders to freq and gain
~tosc_2.addCtl(\gain2, \fader, nil, ControlSpec(-80,-0.5,\db,0,0,"dB"))
~tosc_2.addCtl(\freq2, \fader, nil, ControlSpec(300, 5000, \exp, units: "Hz"));

// connect \freq2 OSC control to \synthfreq parameter of ~synth2
~tosc_2.connect( ~synth2, \freq2, \synthfreq)

// connect \gain2 OSC control to amp of the synth via the function
~tosc_2.connect( ~synth2, \gain2, {|obj, val| obj.amp_(val.dbamp)});
~tosc_2.disconnect( \gain2 ); // disconnect from object, though still mapped by its ControlSpec

// send \gain2 amp control to the first synth instead
// (the former mapping of \gain2 is removed)
~tosc_2.connect( ~synth1, \gain2, {|obj, val| obj.amp_(val.dbamp)});
// now both \gain1 and \gain2 can chaing ~synth1's amplitude


/* clean up */

~tosc_1.free
~tosc_2.free

~synth1.free
~synth2.free


// TODO:
// handle default control values from ControlSpec on loading
// if one TouchOsc overwrites the control of another, retain the previous control once the replacer control is freed