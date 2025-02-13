1. Go through each function on Controller and change it so that it outputs a string or JSON object instead of printing out result to terminal, apart from the Error messages complete action functions
    - Show Commands (DONE)
    - Delete Commands (DONE)
    - Create Commands (DONE)
    - Update Commands (DONE)
    - Parallel Commands (DONE)
    - Consume Commands (DONE)
2. Hashmaps of Tributary objects instead of Lists, memory yes higher but efficiency more
3. Change Error Messages to Exceptions
    - idk how this will affect the system, maybe I have to catch to catch the error in the Springboot application
4. Build spring boot App with the same number of endpoints as CLI commands
5. Move Production to producer classes, 100% makes sense


Low Priority Changes:
- Fix the way the security Keys are stored
    - Possibly initialize them in the Cluster class
- Add Byte-Payload Handling