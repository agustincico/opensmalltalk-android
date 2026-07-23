"dev-tests.st — text smoke tests for the Android Cuis dev loop.

HOW IT RUNS: the app appends  -s <filesDir>/dev-tests.st  to the VM argv, but
ONLY when this file is present in the app's filesDir (see squeak_jni.c). So the
image evaluates it at startup and its output goes to stdout -> Android logcat
(tag SQUEAK_VM). push-image.sh --st scripts/loop/dev-tests.st puts it there.

REQUIRES a Cuis 6.x image: Cuis 5.0/4507 has no -s/-d command-line support, and
creating classes this early in startup hangs — so tests are plain assertions
(no class definitions), which is all we need.

OUTPUT: every line is prefixed DEVTEST so observe.sh surfaces it. Use PASS/FAIL/
ERROR + a final SUMMARY. Edit freely and re-push; no APK rebuild needed."

| out pass fail check |
out := StdIOWriteStream stdout.
pass := 0. fail := 0.

"check: label + a zero-arg block. Errors are caught and count as failures,
 so one broken assertion never aborts the whole run."
check := [ :label :condBlock |
	| ok |
	ok := [ condBlock value = true ]
		on: Error
		do: [ :e |
			out nextPutAll: 'DEVTEST ERROR ', label, ' :: ', (e messageText ifNil: [ '<nil>' ]); newLine; flush.
			false ].
	ok
		ifTrue:  [ pass := pass + 1. out nextPutAll: 'DEVTEST PASS ', label ]
		ifFalse: [ fail := fail + 1. out nextPutAll: 'DEVTEST FAIL ', label ].
	out newLine; flush ].

out nextPutAll: 'DEVTEST BEGIN'; newLine; flush.

"--- assertions (edit these) ---------------------------------------------"
check value: 'arithmetic'      value: [ 3 + 4 = 7 ].
check value: 'string reverse'  value: [ 'abc' reversed = 'cba' ].
check value: 'collection sum'   value: [ (Array with: 1 with: 2 with: 3) sum = 6 ].
check value: 'Object global'    value: [ Smalltalk includesKey: #Object ].
check value: 'Morph exists'     value: [ Smalltalk includesKey: #Morph ].
"-------------------------------------------------------------------------"

out nextPutAll: 'DEVTEST SUMMARY pass=', pass printString, ' fail=', fail printString; newLine; flush.
