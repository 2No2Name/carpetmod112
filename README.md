# Carpet Mod with HopperOptimizations
Forked from Carpet Mod for 1.12.2
## Features

Use /carpet to enable:

### optimizedInventories

Optimized Inventory accesses - bloomfilters, cached BlockEntities and improved item transfers. All mechanics should work the same as in vanilla, just with less lag.


### optimizedItemStackEmptyCheck

Speeds up checking whether an itemStack is empty by using cached information from vanilla. Mechanics like vanilla. 


### optimizedEntityHopperInteraction

Reworked interaction between hoppers and entities. Entities look for hoppers instead of hoppers searching for entities. This change should be barely detectable - the order in which items are picked up might be *slightly different from vanilla*. (For stationary items the order is mostly "oldest first", which is very similar to vanilla)


### failedTransferNoComparatorUpdates

Removes comparator updates when item transfers fail. This change is detectable with redstone and therefore clearly *non-vanilla behavior*.


### debugOptimizedInventories

Checks the consistency of internal datastructures used in optimizedInventories on every access. Lots of computational overhead. Will spam console with debug outputs when a bug is detected. 



## Getting Started
### Setting up your sources
- Clone this repository.
- Run `gradlew setupCarpetmod` in the root project directory.

### Using an IDE
- To use Eclipse, run `gradlew eclipse`, then import the project in Eclipse.
- To use Intellij, run `gradlew idea`, then import the project in Intellij.

## Using the build system
Edit the files in the `src` folder, like you would for a normal project. The only special things you have to do are as follows:
### To generate patch files so they show up in version control
Use `gradlew genPatches`
### To apply patches after pulling
Use `gradlew setupCarpetmod`. It WILL overwrite your local changes to src, so be careful.
### To create a release / patch files
In case you made changes to the local copy of the code in `src`, run `genPatches` to update the project according to your src.
Use `gradlew createRelease`. The release will be a ZIP file containing all modified classes, obfuscated, in the `build/distributions` folder.
### To run the server locally (Windows)
Use `mktest.cmd` to run the modified server with generated patches as a localhost server. It requires `gradlew createRelease` to finish successfully as well as using default paths for your minecraft installation folder.

In case you use different paths, you might need to modify the build script.
This will leave a ready server jar file in your saves folder.

It requires to have 7za installed in your paths
