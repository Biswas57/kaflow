
1. Build spring boot App with the same number of endpoints as CLI commands (IN PROGRESS)
    - Fix how Parameter are Parsed because currently parsing the JSON object from something like A curl command and an attached JSON object is not working, so I should just change all API params to a JSON object
        - like what we did w Flask -> `@app.route("/api/url-generate", methods=["POST"])`
        - Also label what each API method is -> `POST, GET or PUT`
    - Create processing helpers for these API calls in `TributaryHelper`
2. Move create event method from message to producer classes, 100% makes sense (DONE)
    - Fixed the way the security Keys are stored -> They are stored in the Topic which a hashmap of keys for each partition
3. Create API-connected CLI App acting kind of as a front end just to test API calls 
4. Fix UML before starting to implement Vector Topic creation automation and load balanced partition creation automation

6. the concurrency changes I need to make
  - Worker thread per topic/connection (up to you)
  - using synchronized write/read functions and thread safe list handling
  - eliminate parallel produce and parallel consume, because if we're gonna get a request in parallel we should do that and not just handle batch requests, batch requests are unlikely to happen in the microservice architecture we're aiming to be a part of
  - I'm not sure whether to use mutex locks or just Collections.synchronized list, design choice is upto you
  - I'm not sure if I have to use complete futures here or not
  - Use either Runnable interface or thread class for the worker thread running in the tributary controller
  - Not sure whether to have 1 thread per action or 1 thread per topic or IDK the relationship right now

7. API interfaces
    - Implementing a HTTP Springboot interface for admin requestions from consumer and producer clients
    - Implementing a stream-like gRPC connection for message productions and consumption
  



Low Priority Changes:
- Make API testing suits and API - CLI interaction Class that calls the API urls and curl them or sum shit
- create domains for both the stream and HTTP JSON connection
- Create UI using admin HTTP connections
- Set up docker container and host on my linux PC
- Make API testing suits and API - CLI interaction Class that calls the API urls and curl them or sum shit
- Update UML
