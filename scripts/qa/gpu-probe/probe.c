#include <jni.h>
#include <vulkan/vulkan.h>
#include <stdio.h>
#include <stdlib.h>
#include <stdint.h>

JNIEXPORT jstring JNICALL Java_qa_nusadesk_gpuprobe_GpuProbe_vulkan(JNIEnv *env, jclass ignored) {
    (void)ignored;
    VkInstance instance=VK_NULL_HANDLE; VkDevice device=VK_NULL_HANDLE;
    VkBuffer buffer=VK_NULL_HANDLE; VkDeviceMemory memory=VK_NULL_HANDLE; VkCommandPool pool=VK_NULL_HANDLE;
    VkResult result=VK_SUCCESS; const char *stage="start"; char report[8192]={0}; size_t used=0;
    VkPhysicalDevice physical=VK_NULL_HANDLE; uint32_t count=0, family=UINT32_MAX, memoryType=UINT32_MAX;
    int verified=0; void *mapped=NULL;
    #define RUN(call) do {stage=#call; result=(call); if(result!=VK_SUCCESS) goto cleanup;} while(0)
    #define ADD(...) do { if(used<sizeof(report)) used+=(size_t)snprintf(report+used,sizeof(report)-used,__VA_ARGS__); } while(0)
    VkApplicationInfo app={.sType=VK_STRUCTURE_TYPE_APPLICATION_INFO,.pApplicationName="NusaDesk QA",.apiVersion=VK_API_VERSION_1_0};
    VkInstanceCreateInfo ici={.sType=VK_STRUCTURE_TYPE_INSTANCE_CREATE_INFO,.pApplicationInfo=&app};
    RUN(vkCreateInstance(&ici,NULL,&instance));
    RUN(vkEnumeratePhysicalDevices(instance,&count,NULL));
    if(!count) {stage="no physical device"; goto cleanup;}
    VkPhysicalDevice *devices=calloc(count,sizeof(*devices));
    RUN(vkEnumeratePhysicalDevices(instance,&count,devices)); physical=devices[0]; free(devices);
    VkPhysicalDeviceProperties props; vkGetPhysicalDeviceProperties(physical,&props);
    ADD("device=%s\napi=%u.%u.%u\ndriver=%u\nvendor=%x\ndeviceType=%u\n",props.deviceName,
        VK_VERSION_MAJOR(props.apiVersion),VK_VERSION_MINOR(props.apiVersion),VK_VERSION_PATCH(props.apiVersion),
        props.driverVersion,props.vendorID,props.deviceType);
    uint32_t extCount=0; RUN(vkEnumerateDeviceExtensionProperties(physical,NULL,&extCount,NULL));
    VkExtensionProperties *exts=calloc(extCount,sizeof(*exts));
    RUN(vkEnumerateDeviceExtensionProperties(physical,NULL,&extCount,exts));
    ADD("extensions="); for(uint32_t i=0;i<extCount;i++) ADD("%s%s",i?",":"",exts[i].extensionName); ADD("\n"); free(exts);
    vkGetPhysicalDeviceQueueFamilyProperties(physical,&count,NULL);
    VkQueueFamilyProperties *queues=calloc(count,sizeof(*queues)); vkGetPhysicalDeviceQueueFamilyProperties(physical,&count,queues);
    for(uint32_t i=0;i<count;i++) if(queues[i].queueFlags&VK_QUEUE_GRAPHICS_BIT) {family=i;break;} free(queues);
    if(family==UINT32_MAX) {stage="no graphics queue";goto cleanup;}
    float priority=1; VkDeviceQueueCreateInfo qci={.sType=VK_STRUCTURE_TYPE_DEVICE_QUEUE_CREATE_INFO,.queueFamilyIndex=family,.queueCount=1,.pQueuePriorities=&priority};
    VkDeviceCreateInfo dci={.sType=VK_STRUCTURE_TYPE_DEVICE_CREATE_INFO,.queueCreateInfoCount=1,.pQueueCreateInfos=&qci};
    RUN(vkCreateDevice(physical,&dci,NULL,&device)); VkQueue queue; vkGetDeviceQueue(device,family,0,&queue);
    VkBufferCreateInfo bci={.sType=VK_STRUCTURE_TYPE_BUFFER_CREATE_INFO,.size=256,.usage=VK_BUFFER_USAGE_TRANSFER_DST_BIT,.sharingMode=VK_SHARING_MODE_EXCLUSIVE};
    RUN(vkCreateBuffer(device,&bci,NULL,&buffer)); VkMemoryRequirements req; vkGetBufferMemoryRequirements(device,buffer,&req);
    VkPhysicalDeviceMemoryProperties mp; vkGetPhysicalDeviceMemoryProperties(physical,&mp);
    for(uint32_t i=0;i<mp.memoryTypeCount;i++) if((req.memoryTypeBits&(1u<<i))&&(mp.memoryTypes[i].propertyFlags&VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT)) {memoryType=i;break;}
    if(memoryType==UINT32_MAX) {stage="no host visible memory";goto cleanup;}
    VkMemoryAllocateInfo mai={.sType=VK_STRUCTURE_TYPE_MEMORY_ALLOCATE_INFO,.allocationSize=req.size,.memoryTypeIndex=memoryType};
    RUN(vkAllocateMemory(device,&mai,NULL,&memory)); RUN(vkBindBufferMemory(device,buffer,memory,0));
    VkCommandPoolCreateInfo pci={.sType=VK_STRUCTURE_TYPE_COMMAND_POOL_CREATE_INFO,.queueFamilyIndex=family}; RUN(vkCreateCommandPool(device,&pci,NULL,&pool));
    VkCommandBuffer command; VkCommandBufferAllocateInfo cai={.sType=VK_STRUCTURE_TYPE_COMMAND_BUFFER_ALLOCATE_INFO,.commandPool=pool,.level=VK_COMMAND_BUFFER_LEVEL_PRIMARY,.commandBufferCount=1};
    RUN(vkAllocateCommandBuffers(device,&cai,&command)); VkCommandBufferBeginInfo begin={.sType=VK_STRUCTURE_TYPE_COMMAND_BUFFER_BEGIN_INFO};
    RUN(vkBeginCommandBuffer(command,&begin)); vkCmdFillBuffer(command,buffer,0,256,0x1234abcd);
    VkMemoryBarrier barrier={.sType=VK_STRUCTURE_TYPE_MEMORY_BARRIER,.srcAccessMask=VK_ACCESS_TRANSFER_WRITE_BIT,.dstAccessMask=VK_ACCESS_HOST_READ_BIT};
    vkCmdPipelineBarrier(command,VK_PIPELINE_STAGE_TRANSFER_BIT,VK_PIPELINE_STAGE_HOST_BIT,0,1,&barrier,0,NULL,0,NULL);
    RUN(vkEndCommandBuffer(command)); VkSubmitInfo submit={.sType=VK_STRUCTURE_TYPE_SUBMIT_INFO,.commandBufferCount=1,.pCommandBuffers=&command};
    RUN(vkQueueSubmit(queue,1,&submit,VK_NULL_HANDLE)); RUN(vkQueueWaitIdle(queue)); RUN(vkMapMemory(device,memory,0,VK_WHOLE_SIZE,0,&mapped));
    VkMappedMemoryRange range={.sType=VK_STRUCTURE_TYPE_MAPPED_MEMORY_RANGE,.memory=memory,.offset=0,.size=VK_WHOLE_SIZE};
    RUN(vkInvalidateMappedMemoryRanges(device,1,&range)); verified=1;
    for(int i=0;i<64;i++) if(((uint32_t*)mapped)[i]!=0x1234abcd) verified=0;
    vkUnmapMemory(device,memory); mapped=NULL; ADD("gpuBufferFillVerified=%s\n",verified?"true":"false");
cleanup:
    ADD("lastStage=%s\nVkResult=%d\n",stage,result);
    if(device) {vkDeviceWaitIdle(device);if(mapped) vkUnmapMemory(device,memory);if(pool)vkDestroyCommandPool(device,pool,NULL);if(buffer)vkDestroyBuffer(device,buffer,NULL);if(memory)vkFreeMemory(device,memory,NULL);vkDestroyDevice(device,NULL);}
    if(instance)vkDestroyInstance(instance,NULL);
    return (*env)->NewStringUTF(env,report);
}
