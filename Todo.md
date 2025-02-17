
1. Build spring boot App with the same number of endpoints as CLI commands (IN PROGRESS)
    - Fix how Parameter are Parsed because currently parsing the JSON object from something like A curl command and an attached JSON object is not working, so I should just change all API params to a JSON object
        - like what we did w Flask -> `@app.route("/api/url-generate", methods=["POST"])`
        - Also label what each API method is -> `POST, GET or PUT`
    - Create processing helpers for these API calls in `TributaryHelper`
2. Move create event method from message to producer classes, 100% makes sense (DONE)
    - Fixed the way the security Keys are stored -> They are stored in the Topic which a hashmap of keys for each partition
3. Create API-connected CLI App acting kind of as a front end just to test API calls
3. Fix UML before starting to implement Vector Topic creation automation and load balanced partition creation automation
5. Going to leave unit testing for this wayyy later after term start but just want this working for now




Low Priority Changes:
- Add Byte-Payload Handling
- Make API testing suits and API - CLI interaction Class that calls the API urls and curl them or sum shit
- Hashmaps of Tributary objects instead of Lists, memory yes higher but efficiency more
    - Lower in priority incase I want vectorise and automate topic creation
    - Possibly better to iterate through Arraylist of Topics rather than Hashmaps

