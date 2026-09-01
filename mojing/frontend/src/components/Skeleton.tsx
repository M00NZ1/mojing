import React from 'react';

interface SkeletonProps {
  className?: string;
  width?: string | number;
  height?: string | number;
  borderRadius?: string | number;
  style?: React.CSSProperties;
}

export function Skeleton({ className = '', width, height, borderRadius, style }: SkeletonProps) {
  const mergedStyle: React.CSSProperties = {
    width: width,
    height: height,
    borderRadius: borderRadius,
    ...style,
  };
  return <div className={`skeleton-pulse ${className}`} style={mergedStyle} />;
}
