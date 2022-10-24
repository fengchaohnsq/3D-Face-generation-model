# 3D Face generation model
3D face generation model original from Basel face project, we made some modifications for our job.

This model is from the simple tool to view the [Basel Face Model 2019](https://faces.dmi.unibas.ch/bfm/bfm2019.html) (compatible with the Basel Face Model 2017 and 2009).

## Requirements
- installed [Java](http://www.oracle.com/technetwork/java/javase/downloads/index.html) (Version 8.0 or higher recommended)
- installed [sbt](http://www.scala-sbt.org/release/tutorial/Setup.html) (only for compiling from sources)

 ![3D Face Generation Model](example.png)
 
## Usage:
- the radio button of 'color' 'shape' 'expression' dimensions used to control whether include the dimension in the model to generate face 
- the spinner which follows those dimension labels controls the number of parameters in each dimension
- the button 'random' will produce a new random face based on those selected dimensions 
- the button 'reset' will update all model parameters
- the radio button of 'yaw' 'pitch' and 'roll' will control the angle of the face, the angle is from -180 to 180.
- the button 'expressions off' will control the expression dimension tab showing or hiding
- the spinner 'number of export images and button 'export PNG' can generate images as you set.

## For Developers:
- clone repository
- compile and run using `sbt run -mem 2000`
