While we started with Gadgetbridge we are probably going to build our own app so we can have a smoother UI, though supporting other device later might be a good idea. Now while we say that, I think we could bring back our finding into GadgetBridge at least for fossil q hybrid (coin-cell) watches. But since we did a lot and that might require many changes in GadgetBridge, including UI step to connect for authentication, the best might be to create an issue describing our findings so they can bring the change in themselves

As for our app, we would like to be able to reuse GadgetBridge permission request system that ask the user at the beginning and go through all steps. Not all permission might be required for us but for those that do, we could follow a same pattern

Else our app want to offer a way to get all the info like in the cli.

But it also need to have the following:

1. allow to add multipel watch

2. select which watch is active (which one will get notifications, etc..)

3. ability to set notifcation type
   we could list the current setup, including the app icon, the vibraiton pattern and the hands configuration
   for each we can delete them or edit them (vibraiton pattern, hour and minute hands degrees)
   then we can add a new one
   it should show the list of app, with a search bar

4. ability to set 16 alarms (16 other will be reserved for something else)

- you can make them repeat on a particular weeekday or a set of weekdays, we could have a shortcut for weekdays and one for weekend

5. ability to sync with a calendar, for now google calendar
   this would read all the calendar events in the next 7 days and set them as alarms (the next 16 available) using specific weekday they will happen (but non-repeating using the undocumented trick)
   This should be synced regularly, so that teh user need to be in touch with the phone at least once a week

6. nudge setting

7. buttons mapping

8. setting step goal on the watch, reading step to check progress

9. setting custom goal on the app, reading from watch to check progress

10. other goal ? sleeping, calories, moves ?

11. more...
