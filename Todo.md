1. Go through each function on Controller and change it so that it outputs a string or JSON object instead of printing out result to terminal, apart from the Error messages complete action functions
    - Show Commands
    - Delete Commands (nothing)
    - Create Commands
    - Update Commands
    - Parallel Commands
2. Hashmaps of Tributary objects instead of Lists, memory yes higher but efficiency more
3. Change Error Messages to Exceptions
    - idk how this will affect the system, maybe I have to catch to catch the error in the Springboot application
4. Build spring boot App with the same number of endpoints as CLI commands


Low Priority Changes:
- Fix the way the security Keys are stored
    - Possibly initialize them in the Cluster class
- Add Byte-Payload Handling