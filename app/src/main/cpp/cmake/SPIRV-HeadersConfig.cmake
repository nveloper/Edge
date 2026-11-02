# Minimal package config so ggml-vulkan's find_package(SPIRV-Headers CONFIG) resolves to the
# vendored third_party/SPIRV-Headers (header-only) instead of requiring a Vulkan SDK install.
get_filename_component(_spirv_root "${CMAKE_CURRENT_LIST_DIR}/../../../../../third_party/SPIRV-Headers" ABSOLUTE)
if (NOT TARGET SPIRV-Headers::SPIRV-Headers)
    add_library(SPIRV-Headers::SPIRV-Headers INTERFACE IMPORTED)
    set_target_properties(SPIRV-Headers::SPIRV-Headers PROPERTIES INTERFACE_INCLUDE_DIRECTORIES "${_spirv_root}/include")
endif()
set(SPIRV-Headers_FOUND TRUE)
