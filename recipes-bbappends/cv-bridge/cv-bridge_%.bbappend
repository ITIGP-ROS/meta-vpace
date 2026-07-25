# since our OpenCV lib is a tegra one (uses CUDA)
# sp we need to inherit the cuda class to make sure the proper flags are set for the build

inherit cuda