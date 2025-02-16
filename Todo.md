1. Go through each function on Controller and change it so that it outputs a string or JSON object instead of printing out result to terminal, apart from the Error messages complete action functions
    - Show Commands (DONE)
    - Delete Commands (DONE)
    - Create Commands (DONE) -> NOT DONE Parse JSON Object thorugh API request create events
    - Update Commands (DONE)
    - Parallel Commands -> NOT DONE Parse JSON Object thorugh API request for create events
    - Consume Commands (DONE)
    - Fix Rebalancing after adding a partition (DONE)
    - Change parsing event ID as a string to parsing the entire event JSONObject, this is because we used to load the JSON from the working directory (won't work in real life because its an API lol) (DONE)
        - parralel produce and consume needs to change its becuase its kinda wonkey fr:
            - using a parts string that has all the strings of each components IDs which is shi for 
            - Having a tedious check to see if there is a partition depending on whether it is a manual or random producer
        - Changing to an array for each component will be better eg. string[] and JSONArray
3. Change Error Messages to Exceptions (DONE)
    - idk how this will affect the system, maybe I have to catch to catch the error in the Springboot application
    - Substep, I gotta change a few of the UPDATE methods because someof them output a group to demonstrate things like rebalancing and new admins and all that shit but worry abt that after.
    - Substep gotta be able to PARSE JSON object when creating events because functionality and eng requirements states that I have A JSON formated information about the Event
4. Build spring boot App with the same number of endpoints as CLI commands (IN PROGRESS)
5. Blog about Vectorized Topic creationg future implementation -> Parse topicid in create event to existing topic and if not found use vectors to compare and make new one
    - Working on Functionality
    - Could remove creat Topic as a whole as this owuld be automated by vector threshold if doesn't exist by analysing JSON object data (source, content, payload type, etc)
    - Hashmap usage could be justified by iterating through keys (topicIDs) but what if its just a name and doesn't provide enough info for a vector comparison between the event being created and the topic
    - Point of this is to automate setup through the automation of creation methods
6. Blog about another load balancing feature that auto detects excessive load on a topic/consumer group or redundate consumers in a consumer group and this can lead to autocreation of extra partitions in the topic and thus rebalancing the topic for consumer groups depending on their rebalancing strategy





Low Priority Changes:
- Fix the way the security Keys are stored
    - Possibly initialize them in the Cluster class
- Add Byte-Payload Handling
- Move from JSON create event to producer classes, 100% makes sense
- Make API testing suits and API - CLI interaction Class that calls the API urls and curl them or sum shit
- Hashmaps of Tributary objects instead of Lists, memory yes higher but efficiency more
    - Lower in priority incase I want vectorise and automate topic creation
    - Possibly better to iterate through Arraylist of Topics rather than Hashmaps
- Fix UML before starting to implement Vector Topic creation automation and load balanced partition creation automation

