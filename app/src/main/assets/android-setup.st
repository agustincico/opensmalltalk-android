"OpenSmalltalk for Android — per-boot image adaptation, passed to the image via -s.
 Everything is guarded (Smalltalk at:#... + on:Error) so Squeak, Cuis and older
 images each apply only what they support and a bad patch never blocks startup.
 Errors/OKs are printed to stdout (Android logcat) when StdIOWriteStream exists."

| out log chain |
out := Smalltalk at: #StdIOWriteStream ifAbsent: [ nil ].
log := [ :msg | out ifNotNil: [ out stdout nextPutAll: 'ANDROID-SETUP ', msg; newLine; flush ] ].

"Cuis: file out silently into the image folder (the app copies fileouts to the
 phone Downloads), and pre-seed author initials so no modal initials prompt pops."
(Smalltalk at: #DirectoryEntry ifAbsent: [ nil ]) ifNotNil: [ :de |
	[ | utils |
	utils := Smalltalk at: #Utilities ifAbsent: [ nil ].
	(utils notNil and: [ (utils authorInitialsPerSe ifNil: [ '' ]) isEmpty ]) ifTrue: [
		utils classPool at: #AuthorInitials put: 'and'.
		utils classPool at: #AuthorName put: 'Android' ].
	de class
		compile: 'userDirectory: userDirectoryDefaultName queryFileName: suggestedFileName writeStreamDo: writeBlock
	| file |
	file := DirectoryEntry smalltalkImageDirectory // suggestedFileName.
	file forceWriteStreamDo: [ :fileStream |
		fileStream ifNotNil: [ writeBlock value: fileStream ] ]'
		classified: 'user default directories'.
	log value: 'cuis-fileout OK' ]
		on: Error do: [ :e | log value: 'cuis ERR ', (e messageText ifNil: [ '?' ]) ] ].

"Squeak: a file dropped into the RUNNING image reaches launchSystemFiles:event:
 (there was no in-app drag, so Squeak reads it as a singleton relaunch and shows
 a 'cannot start a second instance' note). File in .st/.cs source instead."
(Smalltalk at: #MorphicProject ifAbsent: [ nil ]) ifNotNil: [ :mp |
	[ | utils |
	utils := Smalltalk at: #Utilities ifAbsent: [ nil ].
	(utils notNil and: [ (utils authorInitials ifNil: [ '' ]) isEmpty ]) ifTrue: [
		utils setAuthorInitials: 'and' ].
	mp
		compile: 'launchSystemFiles: fileStreams event: genericMorphicEvent
	"OpenSmalltalk for Android: a file dropped into the running image lands here.
	 File in Smalltalk source instead of the singleton-relaunch note."
	fileStreams do: [ :each |
		| nm |
		nm := [ each localName asString asLowercase ] on: Error do: [ '''' ].
		((nm endsWith: ''.st'') or: [ nm endsWith: ''.cs'' ])
			ifTrue: [
				[ each fileIn.
				Transcript showln: ''Filed in '', each localName ]
					on: Error do: [ :e | self inform: ''File-in failed: '', (e messageText ifNil: [ ''?'' ]) ] ]
			ifFalse: [ self inform: ''Cannot open '', nm ] ]'
		classified: 'utilities'.
	log value: 'squeak-drop OK' ]
		on: Error do: [ :e | log value: 'squeak ERR ', (e messageText ifNil: [ '?' ]) ] ].

"Run any queued startup scripts (the File-in fallback, dev-tests), on either image."
chain := [ :name |
	[ | src |
	src := (Smalltalk at: #DirectoryEntry ifAbsent: [ nil ])
		ifNotNil: [ :de | | fe |
			fe := de smalltalkImageDirectory // name.
			fe exists ifTrue: [ fe textContents ] ifFalse: [ nil ] ]
		ifNil: [ (FileStream oldFileOrNoneNamed: name) ifNotNil: [ :s | | c |
			c := s contentsOfEntireFile. s close. c ] ].
	src ifNotNil: [ Compiler evaluate: src ] ]
		on: Error do: [ :e | log value: name, ' ERR ', (e messageText ifNil: [ '?' ]) ] ].
chain value: 'pending-filein.st'.
chain value: 'dev-tests.st'.
